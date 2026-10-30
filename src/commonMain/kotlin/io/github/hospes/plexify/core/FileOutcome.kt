package io.github.hospes.plexify.core

import kotlinx.io.files.Path

/**
 * What became of one file, or of a source path that couldn't be processed. [MediaProcessor] prints each outcome as one
 * status line and counts it in its [MediaProcessor.Stats], so every file is counted exactly once.
 */
sealed interface FileOutcome {

    /** Moved or hardlinked to [target] (in test mode, would be). [replaced]: a different file there was replaced. */
    data class Organized(val target: Path, val replaced: Boolean = false) : FileOutcome

    /** The file already is at [target], or is a hardlink of it: nothing was touched. Counted as skipped. */
    data class AlreadyInPlace(val target: Path) : FileOutcome

    /** Left alone because of [reason]: no match, no such episode or season, a target taken, not a media file. */
    data class Skipped(val reason: String) : FileOutcome

    /**
     * A lookup, the organizing or the source itself failed, as [reason] says. [error] is an unexpected one, whose stack
     * trace verbose mode prints.
     */
    data class Failed(val reason: String, val error: Throwable? = null) : FileOutcome
}
