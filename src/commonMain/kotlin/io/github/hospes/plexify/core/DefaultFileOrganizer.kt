package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.service.PathFormatter
import io.github.hospes.plexify.domain.strategy.NamingStrategy
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
    ): Result<Path> = Result.runCatching {
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
        }
        val finalPath = Path(destinationRoot, relativePath.toString())

        // Ensure the parent directory for the destination file exists
        val parentDir = finalPath.parent
        if (parentDir != null) {
            SystemFileSystem.createDirectories(parentDir, false)
        } else {
            throw IllegalStateException("Could not determine parent directory for $finalPath")
        }

        when (mode) {
            OperationMode.MOVE -> SystemFileSystem.atomicMove(sourceFile, finalPath)
            OperationMode.HARDLINK -> createHardLink(source = sourceFile, destination = finalPath)
        }

        finalPath
    }
}