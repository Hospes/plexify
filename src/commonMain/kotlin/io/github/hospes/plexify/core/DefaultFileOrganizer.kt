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
    /** Replace a different file already at the target instead of skipping it. Never applies to the source itself. */
    private val overwrite: Boolean = false,
) : FileOrganizer {

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

        // Never replace the source itself: when plexify runs over its own library, the target can be the
        // source (or a hardlink to it), and replacing it would lose the only copy. Another file at the target
        // is replaced only when overwrite is enabled.
        val targetExists = SystemFileSystem.exists(finalPath)
        if (targetExists) {
            if (isSameFileOnDisk(sourceFile, finalPath)) return@runCatching OrganizeOutcome.AlreadyInPlace(finalPath)
            if (!overwrite) return@runCatching OrganizeOutcome.TargetExists(finalPath)
        }
        val outcome = if (targetExists) OrganizeOutcome.Replaced(finalPath) else OrganizeOutcome.Organized(finalPath)

        if (isTestMode) return@runCatching outcome

        // Ensure the parent directory for the destination file exists
        val parentDir = finalPath.parent
        if (parentDir != null) {
            SystemFileSystem.createDirectories(parentDir, false)
        } else {
            throw IllegalStateException("Could not determine parent directory for $finalPath")
        }

        when (mode) {
            // atomicMove replaces an existing target in one step.
            OperationMode.MOVE -> SystemFileSystem.atomicMove(sourceFile, finalPath)
            OperationMode.HARDLINK -> if (targetExists) replaceWithHardLink(sourceFile, finalPath) else hardLink(sourceFile, finalPath)
        }

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
     * Links [source] under a temporary name next to [target], then renames it over [target], so the existing
     * file stays in place if linking fails. The temporary name is short and fixed-length, so it can't push the
     * path past a length limit (Windows MAX_PATH) that the target itself fits in.
     */
    private fun replaceWithHardLink(source: Path, target: Path) {
        val temporary = Path(target.parent!!, ".plexify-${Random.nextInt().toUInt().toString(16).padStart(8, '0')}.tmp")
        hardLink(source, temporary)
        try {
            SystemFileSystem.atomicMove(temporary, target)
        } catch (e: Exception) {
            SystemFileSystem.delete(temporary, mustExist = false)
            throw IOException("Replacing '$target' failed: ${e.message}", e)
        }
    }

    private fun isSameFileOnDisk(source: Path, target: Path): Boolean =
        SystemFileSystem.resolve(source) == SystemFileSystem.resolve(target) || isSameFile(source, target)
}
