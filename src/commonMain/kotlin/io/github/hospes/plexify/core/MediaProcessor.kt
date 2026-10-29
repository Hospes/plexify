package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.matching.MatchRanker
import io.github.hospes.plexify.domain.matching.RankedMatch
import io.github.hospes.plexify.domain.matching.format
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
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

        val movieById = findMovieById(parsedInfo).getOrElse { error -> return@indent lookupFailed(source, error) }
        if (movieById != null) {
            debug("Found match: $movieById")
            organizeFile(source, destination, movieById, parsedInfo, mode, isTestMode)
            return@indent
        }

        val title = parsedInfo.title
        val (results, ranking) = lookUpMovie(title, parsedInfo.year)
            .getOrElse { error -> return@indent lookupFailed(source, error) }

        if (results.isEmpty()) {
            status("✗ ${source.name} — no metadata found for '$title'")
            stats.skipped++
            return@indent
        }

        val canonicalMovie = (ranking.firstOrNull()?.media as? CanonicalMedia.Movie)?.let { metadata.withImdbId(it) }
            ?.getOrElse { error -> return@indent lookupFailed(source, error) }

        if (canonicalMovie == null) {
            status("✗ ${source.name} — no confident match for '${parsedInfo.title}'")
            stats.skipped++
            return@indent
        }

        debug("Found match: $canonicalMovie")
        organizeFile(source, destination, canonicalMovie, parsedInfo, mode, isTestMode)
    }

    /**
     * Every movie result found for [title] and [year], with the ranked candidates. Fails when a search
     * failed and nothing else found a candidate.
     *
     * The general search returns one page and ignores the year, so when its best match is not from
     * the filename year, two more searches follow. The same title searched with the year finds a
     * remade film missing from that page. Then, unless the user set the title or the year, the year
     * is read as part of the title ("Wonder.Woman.1984.1080p"), and that match wins if it scores higher.
     */
    context(_: LoggingContext)
    private suspend fun lookUpMovie(
        title: String,
        year: String?,
    ): Result<Pair<List<MediaSearchResult>, List<RankedMatch>>> {
        val first = search.searchFor<MediaSearchResult.Movie>(title, year) { metadata.search(title, year) }
            .getOrElse { return Result.failure(it) }
        val firstRanking = search.rank(first, title, year, reportMiss = year == null).matches
        if (year == null || firstRanking.bestYear() == year) return Result.success(first to firstRanking)

        // A search that fails after an earlier one found a candidate is left out; with no candidate
        // yet, it decides the file.
        suspend fun moviesUnlessFailed(found: List<RankedMatch>, searchMovies: suspend () -> Result<List<MediaSearchResult.Movie>>) =
            searchMovies().fold(
                onSuccess = { Result.success(it) },
                onFailure = { error ->
                    if (found.isEmpty()) return@fold Result.failure(error)
                    debug("Search failed: ${error.message}")
                    Result.success(emptyList())
                },
            )

        debug("No match from $year among the first results; searching again with the year")
        val byYear = moviesUnlessFailed(firstRanking) {
            search.searchFor<MediaSearchResult.Movie>(title, year) { metadata.searchMovies(title, year) }
        }
            .getOrElse { return Result.failure(it) }
        val merged = (first + byYear).distinctBy { listOf(it.provider, it.tmdbId ?: "${it.title}:${it.year}") }
        val fullRanking = search.rank(merged, title, year, reportMiss = false)
        val ranking = fullRanking.matches

        if (ranking.bestYear() != year && titleOverride == null && yearOverride == null) {
            val joinedTitle = "$title $year"
            // Only a film whose title has the year: the reading has no year to rule out the namesakes
            // the first one rejected ("Dracula" for "Dracula 1974").
            val namesYear = { media: CanonicalMedia -> year in (media as CanonicalMedia.Movie).title }
            debug("Still no match from $year; trying '$joinedTitle' as the whole title")
            val joined = moviesUnlessFailed(ranking) {
                search.searchFor<MediaSearchResult.Movie>(joinedTitle, null, namesYear) { metadata.search(joinedTitle, null) }
            }
                .getOrElse { return Result.failure(it) }
            val joinedRanking = search.rank(joined, joinedTitle, null, reportMiss = false).matches.filter { namesYear(it.media) }
            val joinedScore = joinedRanking.firstOrNull()?.score
            if (joinedScore != null && joinedScore > (ranking.firstOrNull()?.score ?: 0.0)) {
                debug("'$joinedTitle' matches better (${joinedScore.format(2)})")
                return Result.success(merged + joined to joinedRanking)
            }
            if (ranking.isEmpty()) search.reportMiss(fullRanking, title)
            return Result.success(merged + joined to ranking)
        }

        if (ranking.isEmpty()) search.reportMiss(fullRanking, title)
        return Result.success(merged to ranking)
    }

    private fun List<RankedMatch>.bestYear(): String? = (firstOrNull()?.media as? CanonicalMedia.Movie)?.year?.toString()

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

    /**
     * The movie the ID in the path names ([ParsedMediaInfo.mediaId]), with its external IDs; null when the path names
     * none or it isn't this file's movie (see [MatchSearch.rankById]), so the title is searched. Fails when the lookup failed.
     */
    context(_: LoggingContext)
    private suspend fun findMovieById(parsed: ParsedMediaInfo.Movie): Result<CanonicalMedia.Movie?> = indent {
        val id = parsed.mediaId ?: return@indent Result.success(null)
        debug("Looking up the movie $id in the path")
        val record = metadata.findMovie(id).getOrElse { return@indent Result.failure(it) }
        val movie = (search.rankById(id, record, "movie", parsed.title, parsed.year)?.media as? CanonicalMedia.Movie)
            ?.let { metadata.withImdbId(it) }?.getOrElse { return@indent Result.failure(it) }
        if (movie != null) status("Matched movie by the ID in its path: ${movie.describe()}")
        Result.success(movie)
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
