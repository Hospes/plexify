package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import kotlinx.io.files.Path

interface FileOrganizer {
    fun organize(
        sourceFile: Path,
        destinationRoot: Path,
        media: CanonicalMedia,
        parsedInfo: ParsedMediaInfo,
        mode: OperationMode,
        isTestMode: Boolean,
    ): Result<OrganizeOutcome>
}

/** What [FileOrganizer.organize] did (or, in test mode, would do) with a file. [path] is the computed target. */
sealed interface OrganizeOutcome {
    val path: Path

    /** The file was moved or hardlinked to [path]. */
    data class Organized(override val path: Path) : OrganizeOutcome

    /** [path] already is the source file: the source sits at its target, or the target is a hardlink to it. Nothing was touched. */
    data class AlreadyInPlace(override val path: Path) : OrganizeOutcome

    /** A different file already occupies [path]. Neither file was touched. */
    data class TargetExists(override val path: Path) : OrganizeOutcome
}
