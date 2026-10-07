package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.service.PathFormatter
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class DefaultFileOrganizer(
    private val pathFormatter: PathFormatter,
    private val namingStrategy: NamingStrategy,
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

        // Never replace what is already at the target. When it's the source itself (plexify run over its own
        // library), linking would delete the only copy; when it's another file, replacing it would lose that file.
        if (SystemFileSystem.exists(finalPath)) {
            return@runCatching if (isSameFileOnDisk(sourceFile, finalPath)) {
                OrganizeOutcome.AlreadyInPlace(finalPath)
            } else {
                OrganizeOutcome.TargetExists(finalPath)
            }
        }

        if (isTestMode) return@runCatching OrganizeOutcome.Organized(finalPath)

        // Ensure the parent directory for the destination file exists
        val parentDir = finalPath.parent
        if (parentDir != null) {
            SystemFileSystem.createDirectories(parentDir, false)
        } else {
            throw IllegalStateException("Could not determine parent directory for $finalPath")
        }

        when (mode) {
            OperationMode.MOVE -> SystemFileSystem.atomicMove(sourceFile, finalPath)
            OperationMode.HARDLINK -> {
                try {
                    createHardLink(source = sourceFile, destination = finalPath)
                } catch (e: Exception) {
                    throw IOException("Hardlink failed: ${e.message}", e)
                }
            }
        }

        OrganizeOutcome.Organized(finalPath)
    }

    private fun isSameFileOnDisk(source: Path, target: Path): Boolean =
        SystemFileSystem.resolve(source) == SystemFileSystem.resolve(target) || isSameFile(source, target)
}
