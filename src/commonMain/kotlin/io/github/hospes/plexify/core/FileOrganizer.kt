package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.OperationMode
import kotlinx.io.files.Path

interface FileOrganizer {
    fun organize(
        sourceFile: Path,
        destinationRoot: Path,
        media: CanonicalMedia,
        mode: OperationMode
    ): Result<Path> // Returns the new path on success
}