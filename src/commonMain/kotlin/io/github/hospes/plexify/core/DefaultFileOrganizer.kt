package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.service.PathFormatter
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class DefaultFileOrganizer(
    private val pathFormatter: PathFormatter,
    private val namingTemplate: String,
) : FileOrganizer {

    override fun organize(
        sourceFile: Path,
        destinationRoot: Path,
        media: CanonicalMedia,
        mode: OperationMode,
    ): Result<Path> = Result.runCatching {
        val relativePath = pathFormatter.format(namingTemplate, media, sourceFile)
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