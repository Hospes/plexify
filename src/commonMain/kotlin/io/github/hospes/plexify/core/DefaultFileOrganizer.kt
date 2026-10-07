package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.service.PathFormatter
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random

class DefaultFileOrganizer(
    private val pathFormatter: PathFormatter,
    private val namingStrategy: NamingStrategy,
    /** Replace a different file already at the target instead of skipping it. Never applies to the source itself, or to a target an earlier file of this run claimed. */
    private val overwrite: Boolean = false,
) : FileOrganizer {

    /** Folders already cleared of leftover temporary files in this run. */
    private val cleanedFolders = mutableSetOf<Path>()

    /**
     * Targets that a file of this run was organized to (or, in test mode, would be) or already was at, each with that
     * source. Nothing is written in test mode, so only this tells a dry run that a later file maps to the same target.
     */
    private val claimedTargets = mutableMapOf<Path, Path>()

    override fun organize(
        sourceFile: Path,
        destinationRoot: Path,
        media: CanonicalMedia,
        parsedInfo: ParsedMediaInfo,
        mode: OperationMode,
        isTestMode: Boolean,
    ): Result<OrganizeOutcome> = Result.runCatching {
        val relativePath = when (media) {
            is CanonicalMedia.Movie -> pathFormatter.formatMoviePath(
                folderTemplate = namingStrategy.movieFolderTemplate,
                fileTemplate = namingStrategy.movieFileTemplate,
                media = media,
                parsedInfo = parsedInfo,
                sourceFile = sourceFile
            )

            is CanonicalMedia.Episode -> pathFormatter.formatEpisodePath(
                showFolderTemplate = namingStrategy.tvShowFolderTemplate,
                seasonFolderTemplate = namingStrategy.seasonFolderTemplate,
                episodeFileTemplate = namingStrategy.episodeFileTemplate,
                media = media,
                parsedInfo = parsedInfo,
                sourceFile = sourceFile
            )

            is CanonicalMedia.TvShow -> throw IllegalArgumentException("TV show is not suppose to be here.")
        }
        val finalPath = Path(destinationRoot, relativePath.toString())

        // A target an earlier file of this run claimed is never replaced: with MOVE that file has no other name. The
        // same file reached again (passed twice, or a hardlink of it) is in place. In test mode the claimant isn't at
        // the target, so it is compared with the source too.
        claimedTargets[finalPath]?.let { claimant ->
            val sameFile = claimant == sourceFile || isSameFileOnDisk(sourceFile, finalPath) || isSameFileOnDisk(sourceFile, claimant)
            return@runCatching if (sameFile) OrganizeOutcome.AlreadyInPlace(finalPath) else OrganizeOutcome.TakenInThisRun(finalPath, claimant)
        }

        // Never replace the source itself: when plexify runs over its own library, the target can be the
        // source (or a hardlink to it), and replacing it would lose the only copy. Another file at the target
        // is replaced only when overwrite is enabled.
        val targetExists = PlatformFileSystem.kind(finalPath) != null
        if (targetExists) {
            if (isSameFileOnDisk(sourceFile, finalPath)) {
                claimedTargets[finalPath] = sourceFile
                return@runCatching OrganizeOutcome.AlreadyInPlace(finalPath)
            }
            if (!overwrite) return@runCatching OrganizeOutcome.TargetExists(finalPath)
        }
        val outcome = if (targetExists) OrganizeOutcome.Replaced(finalPath) else OrganizeOutcome.Organized(finalPath)

        if (isTestMode) {
            claimedTargets[finalPath] = sourceFile
            return@runCatching outcome
        }

        // Ensure the parent directory for the destination file exists
        val parentDir = finalPath.parent
        if (parentDir != null) {
            PlatformFileSystem.createDirectories(parentDir)
        } else {
            throw IllegalStateException("Could not determine parent directory for $finalPath")
        }
        if (cleanedFolders.add(parentDir)) deleteLeftoverTemporaryFiles(parentDir)

        when (mode) {
            // atomicMove replaces an existing target in one step.
            OperationMode.MOVE -> {
                val linkTarget = resolveSymbolicLink(sourceFile)
                if (linkTarget != null) moveSymbolicLink(sourceFile, linkTarget, finalPath)
                else PlatformFileSystem.atomicMove(sourceFile, finalPath)
            }
            // createHardLink links the file a symlink points at, never the symlink itself.
            OperationMode.HARDLINK -> if (targetExists) replaceWithHardLink(sourceFile, finalPath) else hardLink(sourceFile, finalPath)
        }

        // Claimed only once in place: after a failure, a later file may still take the target.
        claimedTargets[finalPath] = sourceFile
        outcome
    }

    private fun hardLink(source: Path, target: Path) {
        try {
            createHardLink(source = source, destination = target)
        } catch (e: Exception) {
            throw IOException("Hardlink failed: ${e.message}", e)
        }
    }

    /**
     * Links [source] under a temporary name next to [target] ([temporaryPath]), then renames it over [target], so the
     * existing file stays in place if linking fails.
     */
    private fun replaceWithHardLink(source: Path, target: Path) {
        val temporary = temporaryPath(target)
        hardLink(source, temporary)
        try {
            PlatformFileSystem.atomicMove(temporary, target)
        } catch (e: Exception) {
            PlatformFileSystem.delete(temporary)
            throw IOException("Replacing '$target' failed: ${e.message}", e)
        }
    }

    /**
     * Moves the symlink [source] itself to [target], as a rename does with any file. A relative link would then
     * point somewhere else from the library, so the link is first moved under a temporary name next to [target] and
     * checked to still lead to [linkTarget]. If it doesn't, it goes back to [source] and the move fails, leaving a
     * file already at [target] in place.
     */
    private fun moveSymbolicLink(source: Path, linkTarget: Path, target: Path) {
        val temporary = temporaryPath(target)
        PlatformFileSystem.atomicMove(source, temporary)
        if (!isSameFile(temporary, linkTarget)) {
            PlatformFileSystem.atomicMove(temporary, source)
            throw IOException(
                "'$source' is a symlink to '$linkTarget' that won't resolve from the library (a relative link?). " +
                    "Use hardlink mode, or make the link absolute."
            )
        }
        try {
            PlatformFileSystem.atomicMove(temporary, target)
        } catch (e: Exception) {
            PlatformFileSystem.atomicMove(temporary, source)
            throw IOException("Moving '$source' to '$target' failed: ${e.message}", e)
        }
    }

    /**
     * Deletes temporary files left in [folder] by a run that was killed between creating one and renaming it into
     * place: a hardlink to a source file (only that name is removed) or a symlink being moved. Files are organized one
     * at a time and each operation removes its own temporary file, so any found here are leftovers. (A second plexify
     * process writing to the same folder at that moment would lose its temporary file, and its replacement would fail
     * with the old file kept.) Failures are ignored: the file is tried again on the next run.
     */
    private fun deleteLeftoverTemporaryFiles(folder: Path) {
        val entries = runCatching { PlatformFileSystem.list(folder) }.getOrDefault(emptyList())
        entries.filter { TEMPORARY_NAME.matches(it.name) }.forEach { entry ->
            runCatching { if (PlatformFileSystem.kind(entry) != FileKind.DIRECTORY) PlatformFileSystem.delete(entry) }
        }
    }

    /**
     * A temporary name next to [target]. It is short and fixed-length, so it can't push the path past a length limit
     * (Windows MAX_PATH) that the target itself fits in. It has no video extension, so a media server scanning the
     * library doesn't take it for a video.
     */
    private fun temporaryPath(target: Path): Path =
        Path(target.parent!!, ".plexify-${Random.nextInt().toUInt().toString(16).padStart(8, '0')}.tmp")

    // kotlinx-io's resolve fails on Windows paths past MAX_PATH; isSameFile also recognizes the same path.
    private fun isSameFileOnDisk(source: Path, target: Path): Boolean =
        runCatching { SystemFileSystem.resolve(source) == SystemFileSystem.resolve(target) }.getOrDefault(false) ||
            isSameFile(source, target)

    private companion object {
        /** Names from [temporaryPath], and nothing else a user might keep in the library. */
        val TEMPORARY_NAME = Regex("""\.plexify-[0-9a-f]{8}\.tmp""")
    }
}
