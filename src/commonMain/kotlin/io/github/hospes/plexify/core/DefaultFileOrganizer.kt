package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.service.ParsedMovieInfo
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
        parsedInfo: ParsedMovieInfo,
        mode: OperationMode,
    ): Result<Path> = Result.runCatching {
        val relativePath = pathFormatter.format(namingStrategy.movieTemplate, media, parsedInfo, sourceFile)
        val finalPath = Path(destinationRoot, relativePath.toString())

        // Ensure the parent directory for the destination file exists
        val parentDir = finalPath.parent
        if (parentDir != null) {
            SystemFileSystem.createDirectories(parentDir, false)
        } else {
            throw IllegalStateException("Could not determine parent directory for $finalPath")
        }

        when (mode) {
            OperationMode.MOVE -> {
                SystemFileSystem.atomicMove(sourceFile, finalPath)
            }

            OperationMode.HARDLINK -> {
                createHardLink(source = sourceFile, destination = finalPath)
            }
        }

        finalPath
    }
}