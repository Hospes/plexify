package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.matching.MatchRanker
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.model.withOverrides
import io.github.hospes.plexify.domain.service.MediaFilenameParser
import io.github.hospes.plexify.logging.LoggingContext
import io.github.hospes.plexify.logging.debug
import io.github.hospes.plexify.logging.indent
import io.github.hospes.plexify.logging.log
import io.github.hospes.plexify.logging.status
import kotlinx.io.files.Path

class MediaProcessor(
    private val metadata: MetadataRepository,
    private val fileOrganizer: FileOrganizer,
    private val titleOverride: String? = null,
    private val seasonOverride: Int? = null,
    private val yearOverride: String? = null,
    private val episodeOffset: Int? = null,
) {
    private val SUPPORTED_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "wmv", "m4v", "mpg", "mpeg", "flv")

    /** The run's counts so far. Only [count] changes them, once for each [FileOutcome]. */
    class Stats(organized: Int = 0, skipped: Int = 0, failed: Int = 0) {
        var organized: Int = organized
            private set
        var skipped: Int = skipped
            private set
        var failed: Int = failed
            private set

        internal fun count(outcome: FileOutcome) {
            when (outcome) {
                is FileOutcome.Organized -> organized++
                is FileOutcome.AlreadyInPlace, is FileOutcome.Skipped -> skipped++
                is FileOutcome.Failed -> failed++
            }
        }
    }

    val stats: Stats = Stats()

    private val search = MatchSearch(metadata, MatchRanker(yearOverride))

    // What the run decided about each show key: the season its title names, its release seasons' shows, notices.
    private val showStates = ShowStates()

    private val showMatcher = ShowMatcher(metadata, search, showStates, titleOverride, yearOverride)

    private val movieMatcher = MovieMatcher(metadata, search, titleOverride, yearOverride)

    private val episodeResolver = EpisodeResolver(metadata, showStates)

    context(_: LoggingContext)
    suspend fun process(source: Path, destination: Path, mode: OperationMode, isTestMode: Boolean) {
        val kind = try {
            PlatformFileSystem.kind(source)
        } catch (e: Exception) {
            return sourceFailed("${e.message}")
        }
        if (kind == null) return sourceFailed("Source path does not exist: $source")
        if (kind == FileKind.DANGLING_LINK) {
            return sourceFailed("Source is a broken symlink, its target doesn't exist or can't be reached: $source")
        }

        if (kind == FileKind.DIRECTORY) {
            var unreadable = false
            val mediaFiles = walkFiles(
                source,
                // Not counted as skipped: its files are all walked at the other path.
                onRevisited = { path, original -> log("Warning: Skipping $path: it leads to $original, already walked") },
            ) { path, error ->
                sourceFailed("Can't read $path (${error.message})")
                unreadable = true
            }
                .filter { it.name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS }
                .toList()

            if (mediaFiles.isEmpty()) {
                // After a read error, "nothing found" would be misleading: there may well be media in it.
                if (!unreadable) log("No supported media files found in: $source")
                return
            }

            log("Processing directory: $source (${mediaFiles.size} media files)")
            mediaFiles.forEachIndexed { index, mediaFile ->
                processFile(mediaFile, destination, mode, isTestMode)
                if (index < mediaFiles.size - 1) {
                    debug("---") // Separator for clarity between files
                }
            }
        } else if (kind == FileKind.REGULAR_FILE) {
            if (source.name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS) {
                processFile(source, destination, mode, isTestMode)
            } else {
                sourceSkipped("File is not a supported media type, skipping: $source")
            }
        } else {
            sourceFailed("Source is not a regular file or directory: $source")
        }
    }

    context(_: LoggingContext)
    private suspend fun processFile(source: Path, destination: Path, mode: OperationMode, isTestMode: Boolean) = indent {
        debug("Processing: $source")
        val parentDirName = source.parent?.name
        val parsed = MediaFilenameParser.parse(source.name, parentDirName, source.parent?.parent?.name)
        indent {
            val outcome = when (val parsedInfo = parsed.withOverrides(titleOverride, seasonOverride, yearOverride, episodeOffset)) {
                is ParsedMediaInfo.Movie -> processMovie(source, destination, mode, parsedInfo, isTestMode)
                is ParsedMediaInfo.Episode -> processEpisode(source, destination, mode, parsedInfo, isTestMode)
            }
            report(source, destination, outcome)
        }
    }

    context(_: LoggingContext)
    private suspend fun processMovie(
        source: Path,
        destination: Path,
        mode: OperationMode,
        parsedInfo: ParsedMediaInfo.Movie,
        isTestMode: Boolean,
    ): FileOutcome {
        debug("Parsed as Movie: Title='${parsedInfo.title}', Year='${parsedInfo.year}'")

        val movie = when (val match = movieMatcher.match(parsedInfo).getOrElse { error -> return lookupFailed(error) }) {
            is MovieMatcher.Match.Found -> match.movie
            MovieMatcher.Match.NoResults -> return FileOutcome.Skipped("no metadata found for '${parsedInfo.title}'")
            MovieMatcher.Match.NoConfidentMatch -> return FileOutcome.Skipped("no confident match for '${parsedInfo.title}'")
        }

        debug("Found match: $movie")
        return organizeFile(source, destination, movie, parsedInfo, mode, isTestMode)
    }

    context(_: LoggingContext)
    private suspend fun processEpisode(
        source: Path,
        destination: Path,
        mode: OperationMode,
        parsed: ParsedMediaInfo.Episode,
        isTestMode: Boolean,
    ): FileOutcome {
        debug("Parsed as TV Show: Show='${parsed.showTitle}', Season: ${parsed.season}, Episode: ${parsed.episode}")

        // Step 1: Find the candidate shows, best first.
        val match = showMatcher.match(parsed).getOrElse { error -> return lookupFailed(error) }
        val parsedInfo = match.parsed
        if (match.shows.isEmpty()) return FileOutcome.Skipped("no confident match for show '${parsedInfo.showTitle}'")
        debug("Found show: ${match.shows.first()}")

        // Step 2: Find the season and the episode.
        val resolution = episodeResolver.resolve(source, parsedInfo, match.shows, match.key)
            .getOrElse { error -> return lookupFailed(error) }
        val episode = when (resolution) {
            is EpisodeResolver.Resolution.Found -> resolution.episode
            is EpisodeResolver.Resolution.Skipped -> return FileOutcome.Skipped(resolution.reason)
        }

        debug("Found episode: ${episode.describe()}")
        return organizeFile(source, destination, episode, parsedInfo, mode, isTestMode)
    }

    /**
     * The lookup itself failed (a search, a season fetch or an IMDb lookup timed out, or hit an HTTP or network error),
     * so the file is a failure, not a miss.
     */
    private fun lookupFailed(error: Throwable) = FileOutcome.Failed("${error.message}")

    private fun organizeFile(
        source: Path,
        destination: Path,
        media: CanonicalMedia,
        parsedInfo: ParsedMediaInfo,
        mode: OperationMode,
        isTestMode: Boolean,
    ): FileOutcome = fileOrganizer.organize(source, destination, media, parsedInfo, mode, isTestMode).fold(
        onSuccess = { outcome ->
            when (outcome) {
                is OrganizeOutcome.Organized -> FileOutcome.Organized(outcome.path)
                is OrganizeOutcome.Replaced -> FileOutcome.Organized(outcome.path, replaced = true)
                is OrganizeOutcome.AlreadyInPlace -> FileOutcome.AlreadyInPlace(outcome.path)
                is OrganizeOutcome.TargetExists -> FileOutcome.Skipped("target already exists: ${outcome.path}")
                is OrganizeOutcome.TakenInThisRun ->
                    FileOutcome.Skipped("same target as ${outcome.claimedBy.name} earlier in this run: ${outcome.path}")
            }
        },
        onFailure = { error -> FileOutcome.Failed("${error.message}", error) },
    )

    /** Prints [outcome] as [source]'s status line and counts it: the one place a file's outcome is reported. */
    context(ctx: LoggingContext)
    private fun report(source: Path, destination: Path, outcome: FileOutcome) {
        when (outcome) {
            is FileOutcome.Organized -> if (outcome.replaced) {
                status("✓ ${source.name} → ${outcome.target.relativeTo(destination)} (replaced existing file)")
                debug("Replaced: ${outcome.target}")
            } else {
                status("✓ ${source.name} → ${outcome.target.relativeTo(destination)}")
                debug("Organized at: ${outcome.target}")
            }

            is FileOutcome.AlreadyInPlace -> status("= ${source.name} — already in the library: ${outcome.target}")
            is FileOutcome.Skipped -> status("✗ ${source.name} — ${outcome.reason}")
            is FileOutcome.Failed -> {
                status("✗ ${source.name} — ${outcome.reason}")
                if (ctx.verbose) outcome.error?.printStackTrace()
            }
        }
        stats.count(outcome)
    }

    /** A source path, or a path in a source folder, that failed: an `Error:` line naming it, counted as a failed file. */
    context(_: LoggingContext)
    private fun sourceFailed(reason: String) {
        log("Error: $reason")
        stats.count(FileOutcome.Failed(reason))
    }

    /** A source path left alone: a `Warning:` line naming it, counted as a skipped file. */
    context(_: LoggingContext)
    private fun sourceSkipped(reason: String) {
        log("Warning: $reason")
        stats.count(FileOutcome.Skipped(reason))
    }
}

// Short human-readable labels for the concise log lines.
internal fun CanonicalMedia.describe(): String = when (this) {
    is CanonicalMedia.Movie -> "$title${year.inParens()}"
    is CanonicalMedia.Episode -> "S${season.pad2()}E${episode.pad2()}${lastEpisode?.let { "-E${it.pad2()}" } ?: ""} - $title"
    is CanonicalMedia.TvShow -> buildString {
        append("$title${year.inParens()}")
        tmdbId?.let { append(" [tmdbid-$it]") }
        imdbId?.let { append(" {imdb-$it}") }
    }
}

private fun Int?.inParens(): String = this?.let { " ($it)" } ?: ""

internal fun Int.pad2(): String = toString().padStart(2, '0')

/**
 * This path relative to [root], for showing a library path without the destination prefix. Falls back to the
 * full path when it is not under [root].
 */
internal fun Path.relativeTo(root: Path): String {
    val full = toString()
    val base = root.toString().trimEnd(*PATH_SEPARATORS)
    if (base.isEmpty() || !full.startsWith(base)) return full
    val rest = full.substring(base.length)
    // "/library-old/x" is not under "/library": the root must end at a separator.
    if (rest.isEmpty() || rest[0] !in PATH_SEPARATORS) return full
    return rest.trimStart(*PATH_SEPARATORS).ifEmpty { full }
}

private val PATH_SEPARATORS = charArrayOf('/', '\\')
