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
    private val fileSystem: MediaFileSystem = PlatformFileSystem,
) : FileOrganizer {

    /** Folders already cleared of leftover temporary files in this run. */
    private val cleanedFolders = mutableSetOf<Path>()

    /** The volume of each folder [folderVolume] read in this run, null where it couldn't be read. */
    private val folderVolumes = mutableMapOf<Path, ULong?>()

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
        // is replaced only when overwrite is enabled. A dangling symlink there is another file too: it is the
        // library's entry for a file whose mount may just be offline.
        val targetExists = fileSystem.kind(finalPath) != null
        if (targetExists) {
            if (isSameFileOnDisk(sourceFile, finalPath)) {
                claimedTargets[finalPath] = sourceFile
                return@runCatching OrganizeOutcome.AlreadyInPlace(finalPath)
            }
            if (!overwrite) return@runCatching OrganizeOutcome.TargetExists(finalPath)
        }
        val outcome = if (targetExists) OrganizeOutcome.Replaced(finalPath) else OrganizeOutcome.Organized(finalPath)

        // Before test mode returns, so a dry run fails what the real run would, and before any folder is created.
        checkSameVolume(sourceFile, finalPath, mode)

        if (isTestMode) {
            claimedTargets[finalPath] = sourceFile
            return@runCatching outcome
        }

        // Ensure the parent directory for the destination file exists
        val parentDir = finalPath.parent
        if (parentDir != null) {
            fileSystem.createDirectories(parentDir)
        } else {
            throw IllegalStateException("Could not determine parent directory for $finalPath")
        }
        if (cleanedFolders.add(parentDir)) deleteLeftoverTemporaryFiles(parentDir)

        when (mode) {
            // atomicMove replaces an existing target in one step.
            OperationMode.MOVE -> {
                val linkTarget = resolveSymbolicLink(sourceFile)
                if (linkTarget != null) moveSymbolicLink(sourceFile, linkTarget, finalPath)
                else fileSystem.atomicMove(sourceFile, finalPath)
            }
            // createHardLink links the file a symlink points at, never the symlink itself.
            OperationMode.HARDLINK -> if (targetExists) replaceWithHardLink(sourceFile, finalPath) else hardLink(sourceFile, finalPath)
        }

        // Claimed only once in place: after a failure, a later file may still take the target.
        claimedTargets[finalPath] = sourceFile
        outcome
    }

    /**
     * Fails when [source] and [target] are on different volumes: neither a hardlink nor a rename can cross them, and
     * plexify doesn't copy. A hardlink links the file a symlink points at, a move renames the link itself, so that is
     * the file whose volume counts. Folders are compared, not files: on overlayfs (Docker) a file can report the device
     * of the layer it comes from while every folder reports the overlay's. Volumes that can't be read pass the check,
     * and the operation itself reports any failure.
     */
    private fun checkSameVolume(source: Path, target: Path, mode: OperationMode) {
        val file = when (mode) {
            OperationMode.HARDLINK -> runCatching { resolveSymbolicLink(source) }.getOrNull() ?: source
            OperationMode.MOVE -> source
        }
        val sourceVolume = folderVolume(file) ?: return
        val targetVolume = folderVolume(target) ?: return
        if (sourceVolume == targetVolume) return
        val operation = when (mode) {
            OperationMode.HARDLINK -> "Can't hardlink to another volume"
            OperationMode.MOVE -> "Can't move to another volume: plexify renames files and doesn't copy them"
        }
        throw IOException(operation + CROSS_VOLUME_HINT)
    }

    /**
     * The volume of the folder holding [path], read from its nearest existing ancestor (a library folder may not exist
     * yet), or null if none can be read. Each folder is read once per run.
     */
    private fun folderVolume(path: Path): ULong? {
        val folder = path.parent ?: Path(".")
        return folderVolumes.getOrPut(folder) {
            val ancestors = generateSequence(folder) { it.parent } + listOfNotNull(Path(".").takeUnless { folder.isAbsolute })
            ancestors.firstOrNull { runCatching { fileSystem.kind(it) == FileKind.DIRECTORY }.getOrDefault(false) }
                ?.let { fileSystem.identity(it)?.volume }
        }
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
            fileSystem.atomicMove(temporary, target)
        } catch (e: Exception) {
            fileSystem.delete(temporary)
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
        val temporary = temporaryPath(target, MOVED_LINK_PREFIX)
        fileSystem.atomicMove(source, temporary)
        if (!isSameFile(temporary, linkTarget, fileSystem)) {
            moveBack(
                temporary, source,
                IOException(
                    "'$source' is a symlink to '$linkTarget' that won't resolve from the library (a relative link?). " +
                        "Use hardlink mode, or make the link absolute."
                ),
            )
        }
        try {
            fileSystem.atomicMove(temporary, target)
        } catch (e: Exception) {
            moveBack(temporary, source, IOException("Moving '$source' to '$target' failed: ${e.message}", e))
        }
    }

    /**
     * Moves the symlink at [temporary] back to [source], then throws [error]. If moving it back fails, the link stays
     * at [temporary], and the error says so, with [error] as its cause. That name is not one
     * [deleteLeftoverTemporaryFiles] removes, so the user's link is never lost.
     */
    private fun moveBack(temporary: Path, source: Path, error: IOException): Nothing {
        try {
            fileSystem.atomicMove(temporary, source)
        } catch (e: Exception) {
            throw IOException(
                "Moving the symlink back to '$source' failed (${e.message?.trimEnd('.')}), so it is at '$temporary': " +
                    "move it back by hand. ${error.message}",
                error,
            )
        }
        throw error
    }

    /**
     * Deletes temporary files left in [folder] by a run that was killed between creating one and renaming it into
     * place: a hardlink to a source file, so only that name is removed. Files are organized one at a time and each
     * operation removes its own temporary file, so any found here are leftovers. (A second plexify process writing to
     * the same folder at that moment would lose its temporary file, and its replacement would fail with the old file
     * kept.) A symlink being moved has a name of its own ([MOVED_LINK_PREFIX]) and is never deleted: it is the user's
     * only link to its file. Versions before 0.6.1 moved it under this name, so a symlink here is never deleted either
     * (the hardlink is to the file a source symlink points at, never a symlink itself). Failures are ignored: the file
     * is tried again on the next run.
     */
    private fun deleteLeftoverTemporaryFiles(folder: Path) {
        val entries = runCatching { fileSystem.list(folder) }.getOrDefault(emptyList())
        entries.filter { TEMPORARY_NAME.matches(it.name) }.forEach { entry ->
            // resolveSymbolicLink also fails on a dangling symlink, which is kept too.
            runCatching {
                if (fileSystem.kind(entry) != FileKind.DIRECTORY && resolveSymbolicLink(entry) == null) fileSystem.delete(entry)
            }
        }
    }

    /**
     * A temporary name next to [target]. It is short and fixed-length, so it can't push the path past a length limit
     * (Windows MAX_PATH) that the target itself fits in. It has no video extension, so a media server scanning the
     * library doesn't take it for a video.
     */
    private fun temporaryPath(target: Path, prefix: String = TEMPORARY_PREFIX): Path =
        Path(target.parent!!, "$prefix${Random.nextInt().toUInt().toString(16).padStart(8, '0')}.tmp")

    // kotlinx-io's resolve fails on Windows paths past MAX_PATH; isSameFile also recognizes the same path.
    private fun isSameFileOnDisk(source: Path, target: Path): Boolean =
        runCatching { SystemFileSystem.resolve(source) == SystemFileSystem.resolve(target) }.getOrDefault(false) ||
            isSameFile(source, target, fileSystem)

    private companion object {
        const val TEMPORARY_PREFIX = ".plexify-"

        /** For a symlink being moved, which [deleteLeftoverTemporaryFiles] must not delete. */
        const val MOVED_LINK_PREFIX = ".plexify-link-"

        /** Names from [temporaryPath] with [TEMPORARY_PREFIX], and nothing else a user might keep in the library. */
        val TEMPORARY_NAME = Regex("""\.plexify-[0-9a-f]{8}\.tmp""")
    }
}
