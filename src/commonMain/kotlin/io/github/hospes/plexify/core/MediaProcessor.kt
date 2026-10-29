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

    class Stats {
        var organized: Int = 0
        var skipped: Int = 0
        var failed: Int = 0
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
            log("Error: ${e.message}")
            stats.failed++
            return
        }
        if (kind == null) {
            log("Error: Source path does not exist: $source")
            stats.failed++
            return
        }
        if (kind == FileKind.DANGLING_LINK) {
            log("Error: Source is a broken symlink, its target doesn't exist or can't be reached: $source")
            stats.failed++
            return
        }

        if (kind == FileKind.DIRECTORY) {
            var unreadable = false
            val mediaFiles = walkFiles(
                source,
                // Not counted as skipped: its files are all walked at the other path.
                onRevisited = { path, original -> log("Warning: Skipping $path: it leads to $original, already walked") },
            ) { path, error ->
                log("Error: Can't read $path (${error.message})")
                stats.failed++
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
                log("Warning: File is not a supported media type, skipping: $source")
                stats.skipped++
            }
        } else {
            log("Error: Source is not a regular file or directory: $source")
            stats.failed++
        }
    }

    context(_: LoggingContext)
    private suspend fun processFile(source: Path, destination: Path, mode: OperationMode, isTestMode: Boolean) = indent {
        debug("Processing: $source")
        val parentDirName = source.parent?.name
        val parsed = MediaFilenameParser.parse(source.name, parentDirName, source.parent?.parent?.name)
        when (val parsedInfo = parsed.withOverrides(titleOverride, seasonOverride, yearOverride, episodeOffset)) {
            is ParsedMediaInfo.Movie -> processMovie(source, destination, mode, parsedInfo, isTestMode)
            is ParsedMediaInfo.Episode -> processEpisode(source, destination, mode, parsedInfo, isTestMode)
        }
    }

    context(_: LoggingContext)
    private suspend fun processMovie(
        source: Path,
        destination: Path,
        mode: OperationMode,
        parsedInfo: ParsedMediaInfo.Movie,
        isTestMode: Boolean,
    ) = indent {
        debug("Parsed as Movie: Title='${parsedInfo.title}', Year='${parsedInfo.year}'")

        val movie = when (val match = movieMatcher.match(parsedInfo).getOrElse { error -> return@indent lookupFailed(source, error) }) {
            is MovieMatcher.Match.Found -> match.movie
            MovieMatcher.Match.NoResults -> {
                status("✗ ${source.name} — no metadata found for '${parsedInfo.title}'")
                stats.skipped++
                return@indent
            }

            MovieMatcher.Match.NoConfidentMatch -> {
                status("✗ ${source.name} — no confident match for '${parsedInfo.title}'")
                stats.skipped++
                return@indent
            }
        }

        debug("Found match: $movie")
        organizeFile(source, destination, movie, parsedInfo, mode, isTestMode)
    }

    context(_: LoggingContext)
    private suspend fun processEpisode(
        source: Path,
        destination: Path,
        mode: OperationMode,
        parsed: ParsedMediaInfo.Episode,
        isTestMode: Boolean,
    ) = indent {
        debug("Parsed as TV Show: Show='${parsed.showTitle}', Season: ${parsed.season}, Episode: ${parsed.episode}")

        // Step 1: Find the candidate shows, best first.
        val match = showMatcher.match(parsed).getOrElse { error -> return@indent lookupFailed(source, error) }
        val parsedInfo = match.parsed
        if (match.shows.isEmpty()) {
            status("✗ ${source.name} — no confident match for show '${parsedInfo.showTitle}'")
            stats.skipped++
            return@indent
        }
        debug("Found show: ${match.shows.first()}")

        // Step 2: Find the season and the episode.
        val resolution = episodeResolver.resolve(source, parsedInfo, match.shows, match.key)
            .getOrElse { error -> return@indent lookupFailed(source, error) }
        val episode = when (resolution) {
            is EpisodeResolver.Resolution.Found -> resolution.episode
            is EpisodeResolver.Resolution.Skipped -> {
                status("✗ ${source.name} — ${resolution.reason}")
                stats.skipped++
                return@indent
            }
        }

        debug("Found episode: ${episode.describe()}")
        organizeFile(source, destination, episode, parsedInfo, mode, isTestMode)
    }

    /**
     * The lookup itself failed (a search, a season fetch or an IMDb lookup timed out, or hit an HTTP or network error),
     * so the file is a failure, not a miss.
     */
    context(_: LoggingContext)
    private fun lookupFailed(source: Path, error: Throwable) {
        status("✗ ${source.name} — ${error.message}")
        stats.failed++
    }

    context(ctx: LoggingContext)
    private fun organizeFile(
        source: Path,
        destination: Path,
        media: CanonicalMedia,
        parsedInfo: ParsedMediaInfo,
        mode: OperationMode,
        isTestMode: Boolean,
    ) = run {
        fileOrganizer.organize(source, destination, media, parsedInfo, mode, isTestMode)
            .onSuccess { outcome ->
                when (outcome) {
                    is OrganizeOutcome.Organized -> {
                        status("✓ ${source.name} → ${outcome.path.relativeTo(destination)}")
                        debug("Organized at: ${outcome.path}")
                        stats.organized++
                    }

                    is OrganizeOutcome.Replaced -> {
                        status("✓ ${source.name} → ${outcome.path.relativeTo(destination)} (replaced existing file)")
                        debug("Replaced: ${outcome.path}")
                        stats.organized++
                    }

                    is OrganizeOutcome.AlreadyInPlace -> {
                        status("= ${source.name} — already in the library: ${outcome.path}")
                        stats.skipped++
                    }

                    is OrganizeOutcome.TargetExists -> {
                        status("✗ ${source.name} — target already exists: ${outcome.path}")
                        stats.skipped++
                    }

                    is OrganizeOutcome.TakenInThisRun -> {
                        status("✗ ${source.name} — same target as ${outcome.claimedBy.name} earlier in this run: ${outcome.path}")
                        stats.skipped++
                    }
                }
            }
            .onFailure { error ->
                status("✗ ${source.name} — ${error.message}")
                stats.failed++
                if (ctx.verbose) error.printStackTrace()
            }
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
