package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.MetadataCache
import io.github.hospes.plexify.domain.matching.MatchScoring
import io.github.hospes.plexify.domain.matching.calculateTitleConfidence
import io.github.hospes.plexify.domain.matching.normalizedTitle
import io.github.hospes.plexify.domain.matching.titleSimilarity
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import io.github.hospes.plexify.domain.model.MediaId
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.model.withOverrides
import io.github.hospes.plexify.domain.service.EpisodeGroupMapper
import io.github.hospes.plexify.domain.service.MediaFilenameParser
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.domain.service.isNamedById
import io.github.hospes.plexify.logging.LoggingContext
import io.github.hospes.plexify.logging.debug
import io.github.hospes.plexify.logging.indent
import io.github.hospes.plexify.logging.log
import io.github.hospes.plexify.logging.status
import kotlinx.io.files.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

class MediaProcessor(
    private val metadataService: MetadataService,
    private val fileOrganizer: FileOrganizer,
    private val cache: MetadataCache,
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

    /** A candidate from [rankMatches]: its record consolidated across providers, and how it matched. */
    internal class RankedMatch(
        val media: CanonicalMedia,
        /** Best title similarity over every title the candidate is known by. */
        val titleSimilarity: Double,
        /** The [yearScore] it got, or null when the filename or the candidate has no year. */
        val yearScore: Double?,
        /** The season the searched title names, when it is that season's own title. */
        val titleSeason: TitleSeason? = null,
        /** The searched title ends in an arc that no title of this candidate places (see [namesUnplacedArc]). */
        val namesUnplacedArc: Boolean = false,
        /** Its match score; at least the confidence minimum. */
        val score: Double = 0.0,
    )

    /** A season named by its own title, e.g. an anime arc: "Kimetsu no Yaiba: Hashira Geiko-hen" is season 5. */
    internal data class TitleSeason(val season: Int, val title: String)

    // Directories already warned about missing season numbers, to avoid repeating the
    // warning for every episode file of the same release.
    private val seasonWarnedDirs = mutableSetOf<String>()

    // What the run decided about each show key: the season its title names, its release seasons' shows, notices.
    private val showStates = ShowStates()

    // Provider shows (by identity) and seasons already reported as mapped (or unmappable) through episode groups.
    private val episodeGroupNotices = mutableSetOf<Pair<String, Int>>()

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

        val canonicalMovie = (ranking.firstOrNull()?.media as? CanonicalMedia.Movie)?.withExternalIds()
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
        val first = metadataService.search(title, year)
            .getOrElse { return Result.failure(it) }
            .filterIsInstance<MediaSearchResult.Movie>()
        val firstRanking = rankMatches(first, title, year, reportMiss = year == null)
        if (year == null || firstRanking.bestYear() == year) return Result.success(first to firstRanking)

        // A search that fails after an earlier one found a candidate is left out; with no candidate
        // yet, it decides the file.
        suspend fun moviesUnlessFailed(found: List<RankedMatch>, search: suspend () -> Result<List<MediaSearchResult>>) =
            search().fold(
                onSuccess = { Result.success(it.filterIsInstance<MediaSearchResult.Movie>()) },
                onFailure = { error ->
                    if (found.isEmpty()) return@fold Result.failure(error)
                    debug("Search failed: ${error.message}")
                    Result.success(emptyList())
                },
            )

        debug("No match from $year among the first results; searching again with the year")
        val byYear = moviesUnlessFailed(firstRanking) { metadataService.searchMovies(title, year) }
            .getOrElse { return Result.failure(it) }
        val merged = (first + byYear).distinctBy { listOf(it.provider, it.tmdbId ?: "${it.title}:${it.year}") }
        val ranking = rankMatches(merged, title, year, reportMiss = false)

        if (ranking.bestYear() != year && titleOverride == null && yearOverride == null) {
            val joinedTitle = "$title $year"
            debug("Still no match from $year; trying '$joinedTitle' as the whole title")
            val joined = moviesUnlessFailed(ranking) { metadataService.search(joinedTitle, null) }
                .getOrElse { return Result.failure(it) }
            // Only a film whose title has the year: the reading has no year to rule out the namesakes
            // the first one rejected ("Dracula" for "Dracula 1974").
            val joinedRanking = rankMatches(joined, joinedTitle, null, reportMiss = false)
                .filter { year in (it.media as CanonicalMedia.Movie).title }
            val joinedScore = joinedRanking.firstOrNull()?.score
            if (joinedScore != null && joinedScore > (ranking.firstOrNull()?.score ?: 0.0)) {
                debug("'$joinedTitle' matches better (${joinedScore.format(2)})")
                return Result.success(merged + joined to joinedRanking)
            }
            if (ranking.isEmpty()) rankMatches(merged, title, year) // reports why nothing matched
            return Result.success(merged + joined to ranking)
        }

        if (ranking.isEmpty()) rankMatches(merged, title, year)
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
        val parsedInfo = withSeasonFromTitle(parsed).getOrElse { error -> return@indent lookupFailed(source, error) }

        // Step 1: Find the candidate shows, best first, using the cache first. The show the path's ID names
        // is the only one: it has no runner-ups to take a season it lacks.
        val byId = findShowById(parsedInfo).getOrElse { error -> return@indent lookupFailed(source, error) }
        val showById = byId?.second
        val searchedKey = ShowKey.Searched(parsedInfo.showTitle, parsedInfo.year)
        val candidates = showById?.let { listOf(it) }
            ?: findOrFetchShows(searchedKey, parsedInfo.bareShowYear)
                .getOrElse { error -> return@indent lookupFailed(source, error) }
        if (candidates.isEmpty()) {
            status("✗ ${source.name} — no confident match for show '${parsedInfo.showTitle}'")
            stats.skipped++
            return@indent
        }
        debug("Found show: ${candidates.first()}")

        // A release named after one season's own title (an anime arc, numbered from 1 with no
        // season in the filenames) is that season, not season 1. A show found by its ID is keyed by
        // the ID too, as two shows may share their cleaned titles.
        val showKey = byId?.first ?: searchedKey
        val state = showStates[showKey]
        val season = parsedInfo.season ?: state.titleSeason?.let { named ->
            if (!state.titleSeasonReported) {
                state.titleSeasonReported = true
                status("No season number in filenames; '${named.title}' is Season ${named.season} of ${candidates.first().describe()}")
            }
            named.season
        } ?: run {
            if (state.namesUnplacedArc) {
                status("✗ ${source.name} — '${parsedInfo.showTitle}' names an arc that matches no season title of ${candidates.first().describe()}; set the season with -s/--season")
                stats.skipped++
                return@indent
            }
            if (seasonWarnedDirs.add(source.parent?.toString() ?: source.name)) {
                status("Warning: No season number found in filenames, defaulting to Season 1 (use -s/--season to set it explicitly).")
            }
            1
        }

        // Step 2: Find the episode details via season-level fetch (fills whole season cache in one call).
        val seasonKey = SeasonKey(showKey, season)
        val firstEpisodeMatch = findEpisode(candidates, seasonKey, parsedInfo.episode)
            .getOrElse { error -> return@indent lookupFailed(source, error) }
        if (firstEpisodeMatch == null) {
            status("✗ ${source.name} — episode S${season}E${parsedInfo.episode} not found")
            stats.skipped++
            return@indent
        }
        val bestEpisodeMatch = parsedInfo.lastEpisode
            ?.let { last ->
                extendToRange(firstEpisodeMatch, candidates, seasonKey, parsedInfo.episode, last, source.name)
                    .getOrElse { error -> return@indent lookupFailed(source, error) }
            }
            ?: firstEpisodeMatch

        debug("Found episode: ${bestEpisodeMatch.describe()}")
        organizeFile(source, destination, bestEpisodeMatch, parsedInfo, mode, isTestMode)
    }

    /**
     * The release read with the number closing its show title as the season ("Devil_May_Cry_2_[01]" is Devil
     * May Cry season 2), when the title without it matches a show that has that season and the whole title
     * matches no other show; else as parsed. "Kaiju No. 8" stays whole (the show has no season 8), and so
     * does "Lupin III" (Lupin the Third is not *Lupin*). Decided once per show title; a failed lookup is
     * not decided and fails the file.
     */
    context(_: LoggingContext)
    private suspend fun withSeasonFromTitle(parsed: ParsedMediaInfo.Episode): Result<ParsedMediaInfo.Episode> {
        val numbered = parsed.numberedSeason ?: return Result.success(parsed)
        // A season from the parent directory or the command line that differs makes the number the title's.
        if (titleOverride != null || (parsed.season != null && parsed.season != numbered.season)) return Result.success(parsed)

        val state = showStates[ShowKey.Searched(parsed.showTitle, parsed.year)]
        val isSeason = state.numberIsSeason ?: run {
            val show = showWithTitleSeason(parsed, numbered).getOrElse { return Result.failure(it) }
            if (show != null) status("No season number in filenames; '${parsed.showTitle}' is Season ${numbered.season} of ${show.describe()}")
            (show != null).also { state.numberIsSeason = it }
        }
        return Result.success(
            if (isSeason) parsed.copy(showTitle = numbered.showTitle, season = numbered.season, numberedSeason = null) else parsed
        )
    }

    /** The show [numbered] names, when it has that season and the whole title names no other show. */
    context(_: LoggingContext)
    private suspend fun showWithTitleSeason(
        parsed: ParsedMediaInfo.Episode,
        numbered: ParsedMediaInfo.NumberedSeason,
    ): Result<CanonicalMedia.TvShow?> = indent {
        debug("Trying '${numbered.showTitle}' Season ${numbered.season} for '${parsed.showTitle}'")
        // With its external IDs now, so the season cached below carries them, as findEpisode expects.
        val show = bestShow(numbered.showTitle, parsed.year).getOrElse { return@indent Result.failure(it) }
            ?.withExternalIds()?.getOrElse { return@indent Result.failure(it) }
            ?: return@indent Result.success(null)
        val season = findOrFetchSeason(show, numbered.season).getOrElse { return@indent Result.failure(it) }
        if (season.episodes.isEmpty()) {
            debug("${show.describe()} has no Season ${numbered.season}; the number is part of the title")
            return@indent Result.success(null)
        }
        val whole = bestShow(parsed.showTitle, parsed.year).getOrElse { return@indent Result.failure(it) }
        if (whole != null && whole.identity != show.identity) {
            debug("'${parsed.showTitle}' is ${whole.describe()}, not ${show.describe()}")
            return@indent Result.success(null)
        }
        Result.success(show)
    }

    /** The best show match for [title]; a reading being tried reports no miss of its own. */
    context(_: LoggingContext)
    private suspend fun bestShow(title: String, year: String?): Result<CanonicalMedia.TvShow?> =
        metadataService.search(title, year).map { results ->
            rankMatches(results.filterIsInstance<MediaSearchResult.TvShow>(), title, year, reportMiss = false)
                .firstOrNull()?.media as? CanonicalMedia.TvShow
        }

    /** The provider show this is, as its seasons and episode groups are cached: its TMDB ID, else IMDb ID, else title. */
    private val CanonicalMedia.TvShow.identity: String get() = tmdbId ?: imdbId ?: title

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
     * Extends the first episode of a multi-episode file ("S01E01-E03") to its whole range. Every other
     * episode is looked up like the first (same show, cached season, episode groups); the range is kept
     * only when they land on consecutive episodes of one provider season, else the file is filed as its
     * first episode. Fails as [findEpisode] does.
     */
    context(_: LoggingContext)
    private suspend fun extendToRange(
        first: CanonicalMedia.Episode,
        candidates: List<CanonicalMedia.TvShow>,
        seasonKey: SeasonKey,
        firstEpisode: Int,
        lastEpisode: Int,
        fileName: String,
    ): Result<CanonicalMedia.Episode> {
        val season = seasonKey.season
        val episodes = mutableListOf(first)
        for (episode in (firstEpisode + 1)..lastEpisode) {
            val match = findEpisode(candidates, seasonKey, episode).getOrElse { return Result.failure(it) }
            val previous = episodes.last()
            if (match == null || match.season != previous.season || match.episode != previous.episode + 1) {
                status(
                    "Warning: ${fileName} — S${season.pad2()}E${episode.pad2()} is not the episode after " +
                            "S${previous.season.pad2()}E${previous.episode.pad2()} in '${first.show.title}'; " +
                            "filing it as S${first.season.pad2()}E${first.episode.pad2()} only."
                )
                return Result.success(first)
            }
            episodes += match
        }
        return Result.success(
            first.copy(
                lastEpisode = episodes.last().episode,
                title = episodes.map { it.title }.distinct().joinToString(" & "),
            )
        )
    }

    /**
     * The shows matching a title, best first (empty when none is confident), checking the cache
     * before searching providers. Only the best is reported; the rest, same-titled shows only
     * (see [canStandInFor]), back up the episode lookup. A failed search is not cached, so the
     * next file of the show searches again.
     */
    context(_: LoggingContext)
    private suspend fun findOrFetchShows(
        key: ShowKey.Searched,
        bareYear: Boolean,
    ): Result<List<CanonicalMedia.TvShow>> = indent {
        val (title, year) = key
        val cacheKey = key.cacheKey
        val cachedShows = cache.getShows(cacheKey)
        if (cachedShows != null) {
            debug("Cache HIT for show: '$title'")
            return@indent Result.success(cachedShows)
        }
        if (cache.isShowFailed(cacheKey)) {
            debug("Cache HIT (negative) for show: '$title'")
            return@indent Result.success(emptyList())
        }
        debug("Cache MISS for show: '$title'. Searching providers...")

        val (searchResults, ranked) = lookUpShows(title, year, bareYear)
            .getOrElse { error -> return@indent Result.failure(error) }

        val shows = if (searchResults.isEmpty()) {
            status("No match for '$title': providers returned no results.")
            emptyList()
        } else {
            val best = ranked.firstOrNull()
            // Only the best match gets its external IDs now; a runner-up gets them if the episode
            // fallback picks it (see findEpisode). A failed lookup fails before anything is kept.
            val bestShow = best?.let { (it.media as CanonicalMedia.TvShow).withExternalIds().getOrElse { error -> return@indent Result.failure(error) } }
            val state = showStates[key]
            state.titleSeason = best?.titleSeason
            if (best?.namesUnplacedArc == true) state.namesUnplacedArc = true
            val runnerUps = ranked.drop(1).filter { candidate ->
                (best != null && candidate.canStandInFor(best)).also { sameShow ->
                    if (!sameShow) debug("Not a fallback for '$title': ${candidate.media.describe()} (title or year differs)")
                }
            }
            listOfNotNull(bestShow) + runnerUps.map { it.media as CanonicalMedia.TvShow }
        }

        if (shows.isNotEmpty()) {
            cache.putShows(cacheKey, shows)
            status("Matched show: ${shows.first().describe()}")
        } else {
            // Negative cache: don't repeat the search (and its log output) for every episode file.
            cache.markShowFailed(cacheKey)
        }

        return@indent Result.success(shows)
    }

    /**
     * The movie the ID in the path names ([ParsedMediaInfo.mediaId]), with its external IDs; null when the path names
     * none or it isn't this file's movie (see [rankById]), so the title is searched. Fails when the lookup failed.
     */
    context(_: LoggingContext)
    private suspend fun findMovieById(parsed: ParsedMediaInfo.Movie): Result<CanonicalMedia.Movie?> = indent {
        val id = parsed.mediaId ?: return@indent Result.success(null)
        debug("Looking up the movie $id in the path")
        val record = metadataService.findMovie(id).getOrElse { return@indent Result.failure(it) }
        val movie = (rankById(id, record, "movie", parsed.title, parsed.year)?.media as? CanonicalMedia.Movie)?.withExternalIds()
            ?.getOrElse { return@indent Result.failure(it) }
        if (movie != null) status("Matched movie by the ID in its path: ${movie.describe()}")
        Result.success(movie)
    }

    /**
     * The show the ID in the path names, with its external IDs and the [ShowKey.ById] it is kept under, looked up once
     * per ID, title and year; null as for [findMovieById]. The season its title names (an anime arc) is kept under that
     * key, as [findOrFetchShows] keeps a searched show's. A failed lookup is not cached, so the next file of the show
     * tries again.
     */
    context(_: LoggingContext)
    private suspend fun findShowById(parsed: ParsedMediaInfo.Episode): Result<Pair<ShowKey.ById, CanonicalMedia.TvShow>?> = indent {
        val id = parsed.mediaId ?: return@indent Result.success(null)
        val key = ShowKey.ById(id, parsed.showTitle, parsed.year)
        val cacheKey = key.cacheKey
        cache.getShows(cacheKey)?.let { return@indent Result.success(key to it.first()) }
        if (cache.isShowFailed(cacheKey)) return@indent Result.success(null)

        debug("Looking up the show $id in the path")
        val record = metadataService.findShow(id).getOrElse { return@indent Result.failure(it) }
        val match = rankById(id, record, "show", parsed.showTitle, parsed.year)
        val show = (match?.media as? CanonicalMedia.TvShow)?.withExternalIds()?.getOrElse { return@indent Result.failure(it) }
        if (match == null || show == null) {
            cache.markShowFailed(cacheKey)
        } else {
            val state = showStates[key]
            state.titleSeason = match.titleSeason
            if (match.namesUnplacedArc) state.namesUnplacedArc = true
            cache.putShows(cacheKey, listOf(show))
            status("Matched show by the ID in its path: ${show.describe()}")
        }
        Result.success(show?.let { key to it })
    }

    /**
     * [record], the [kind] the ID in the path names, ranked as a search result for the parsed title and year is (by
     * every title it is known by, the year, the confidence minimum), so a tag that isn't this file's (a show's tag on a
     * movie, a wrong one) gives way to the title search. A title named by its ID ("tmdb-1721501") has no title to rank:
     * the ID holds when the record's title is one plexify names by its ID and its year doesn't contradict the name's.
     * Null, with the reason reported, when the ID doesn't hold or names nothing (a merged or deleted record).
     */
    context(_: LoggingContext)
    private fun rankById(id: MediaId, record: MediaSearchResult?, kind: String, parsedTitle: String, parsedYear: String?): RankedMatch? {
        if (record == null) {
            status("No $kind has the ID $id in the path; matching by title")
            return null
        }
        val found = "'${record.title}'${record.year?.let { " ($it)" } ?: ""}"
        if (!id.fromTitle) {
            val confidence = record.allTitles.maxOf { calculateTitleConfidence(parsedTitle, it) }
            val ranked = when (record) {
                is MediaSearchResult.Movie -> record.copy(matchConfidence = confidence)
                is MediaSearchResult.TvShow -> record.copy(matchConfidence = confidence)
            }
            return rankMatches(listOf(ranked), parsedTitle, parsedYear, reportMiss = false).firstOrNull() ?: run {
                status("Warning: the ID $id in the path is the $kind $found, not '$parsedTitle'${parsedYear?.let { " ($it)" } ?: ""}; matching by title")
                null
            }
        }
        if (!isNamedById(record.title)) {
            debug("'$id' is a title: the $kind $id is $found")
            return null
        }
        val parsedY = parsedYear?.toIntOrNull()
        val recordY = record.year?.toIntOrNull()
        if (parsedY != null && recordY != null && yearScore(parsedY, recordY, isShow = record is MediaSearchResult.TvShow) < 0) {
            status("Warning: the ID $id in the path is the $kind $found, not from $parsedY; matching by title")
            return null
        }
        return RankedMatch(consolidate(listOf(record), parsedYear), titleSimilarity = 1.0, yearScore = null)
    }

    /**
     * Every show result found for [title] and [year], with the ranked candidates. A [bareYear] closed the
     * show name without brackets, so unless the user set the title or the year, it may belong to the title:
     * when no show from that year matches, the name is also searched with the year as part of the title
     * ("Space.1999.S01E01" is *Space: 1999*), and that match wins if it scores higher.
     */
    context(_: LoggingContext)
    private suspend fun lookUpShows(
        title: String,
        year: String?,
        bareYear: Boolean,
    ): Result<Pair<List<MediaSearchResult>, List<RankedMatch>>> {
        val found = metadataService.search(title, year)
            .getOrElse { return Result.failure(it) }
            .filterIsInstance<MediaSearchResult.TvShow>()
        val titleYear = year?.takeIf { bareYear && titleOverride == null && yearOverride == null }
        val ranking = rankMatches(found, title, year, reportMiss = titleYear == null).filter { it.media is CanonicalMedia.TvShow }
        if (titleYear == null || (ranking.firstOrNull()?.media as? CanonicalMedia.TvShow)?.year?.toString() == titleYear) {
            return Result.success(found to ranking)
        }

        val joinedTitle = "$title $titleYear"
        debug("No show from $titleYear matched; trying '$joinedTitle' as the whole title")
        val joined = metadataService.search(joinedTitle, null).fold(
            onSuccess = { it.filterIsInstance<MediaSearchResult.TvShow>() },
            onFailure = { error ->
                // With no candidate yet, the failed search decides the file.
                if (ranking.isEmpty()) return Result.failure(error)
                debug("Search failed: ${error.message}")
                emptyList()
            },
        )
        // Only a show whose title has the year: the reading has no year to rule out other shows.
        val joinedRanking = rankMatches(joined, joinedTitle, null, reportMiss = false)
            .filter { (it.media as? CanonicalMedia.TvShow)?.title?.contains(titleYear) == true }
        val joinedScore = joinedRanking.firstOrNull()?.score
        if (joinedScore != null && joinedScore > (ranking.firstOrNull()?.score ?: 0.0)) {
            debug("'$joinedTitle' matches better (${joinedScore.format(2)})")
            return Result.success(found + joined to joinedRanking)
        }
        if (ranking.isEmpty()) rankMatches(found, title, year) // reports why nothing matched
        return Result.success(found + joined to ranking)
    }

    /**
     * Finds the episode in the best-matching show. When that show has no such season at all, it is
     * not the release's show (e.g. a same-titled spin-off with one season), so the same-titled
     * runner-up shows are tried and the first that has the exact episode is used. The show that places a season is
     * kept for the rest of that season's files, so one season never splits across shows. Fails when a
     * season could not be loaded: that says nothing about the episode, so the file is a failure, not a miss.
     */
    context(_: LoggingContext)
    private suspend fun findEpisode(
        candidates: List<CanonicalMedia.TvShow>,
        seasonKey: SeasonKey,
        episode: Int,
    ): Result<CanonicalMedia.Episode?> {
        val season = seasonKey.season
        // A runner-up's season was fetched before its external IDs were, so its cached episodes
        // carry the show without them: hand out the season's show as decided.
        showStates.seasonShow(seasonKey)?.let { show -> return findOrFetchEpisode(show, season, episode).map { it?.copy(show = show) } }

        val primary = candidates.first()
        val primaryMatch = findOrFetchEpisode(primary, season, episode).getOrElse { return Result.failure(it) }
        if (primaryMatch != null) {
            showStates.placeSeason(seasonKey, primary)
            return Result.success(primaryMatch)
        }

        // The show has the season, just not this episode: a numbering quirk, not evidence of a wrong show.
        if (!isSeasonMissing(primary, season)) return Result.success(null)

        for (candidate in candidates.drop(1).take(MatchScoring.MAX_FALLBACK_SHOWS)) {
            // A runner-up whose season fails to load fails the file: whether it has the season is
            // unknown, and taking the next one could file the season under the wrong show.
            val candidateSeason = indent { findOrFetchSeason(candidate, season) }.getOrElse { return Result.failure(it) }
            val match = candidateSeason.episodes.firstOrNull { it.episode == episode } ?: continue
            val alternative = candidate.withExternalIds().getOrElse { return Result.failure(it) }
            showStates.placeSeason(seasonKey, alternative)
            status("Season $season is not in ${primary.describe()}; matched show: ${alternative.describe()}")
            return Result.success(match.copy(show = alternative))
        }
        return Result.success(null)
    }

    /**
     * Fetches the whole season (one API call) and returns the requested episode.
     * Subsequent episodes from the same season are served from cache.
     */
    context(_: LoggingContext)
    private suspend fun findOrFetchEpisode(show: CanonicalMedia.TvShow, season: Int, episode: Int): Result<CanonicalMedia.Episode?> = indent {
        val cachedSeason = findOrFetchSeason(show, season).getOrElse { return@indent Result.failure(it) }

        val match = cachedSeason.episodes.firstOrNull { it.episode == episode }
        if (match != null) return@indent Result.success(match)

        debug("Episode E${episode} not found in S${season} data. Trying episode groups...")
        findViaEpisodeGroups(show, season, episode, providerHasSeason = cachedSeason.episodes.isNotEmpty())
    }

    /**
     * The whole season, fetched once per show and season; empty when the providers report it as
     * nonexistent. A failed fetch is not cached, so the next file of the season tries again.
     */
    context(_: LoggingContext)
    private suspend fun findOrFetchSeason(show: CanonicalMedia.TvShow, season: Int): Result<CanonicalMedia.Season> {
        val cacheKey = show.seasonCacheKey(season)

        cache.getSeason(cacheKey)?.let { cachedSeason ->
            if (cachedSeason.episodes.isNotEmpty()) {
                debug("Cache HIT for S${season} (${cachedSeason.episodes.size} episodes loaded)")
            }
            return Result.success(cachedSeason)
        }

        debug("Cache MISS for season: S${season}. Fetching from providers...")
        return metadataService.getSeason(show, season).onSuccess { cache.putSeason(cacheKey, it) }
    }

    /** True when the providers reported the season as nonexistent (a failed fetch is not cached). */
    private suspend fun isSeasonMissing(show: CanonicalMedia.TvShow, season: Int): Boolean =
        cache.getSeason(show.seasonCacheKey(season))?.episodes?.isEmpty() == true

    /**
     * Whether this runner-up may take a season the best match lacks: only a show named as the
     * release is, as closely as the best match (a same-titled show, like Being Human UK and US),
     * and not contradicted by the filename year. A merely similar title clears the confidence
     * minimum too, but it is a different show.
     */
    private fun RankedMatch.canStandInFor(best: RankedMatch): Boolean =
        titleSimilarity >= best.titleSimilarity && (yearScore == null || yearScore > 0)

    private fun CanonicalMedia.TvShow.seasonCacheKey(season: Int): String = "$identity:$season"

    /**
     * Fallback for releases numbered differently from the provider, typically anime split into
     * cours: the release's S2E01 is TMDB's S1E13. Episode groups (fetched once per show) record
     * that split; the episode found is the provider's own, so the file gets TMDB's numbering. Fails
     * when the groups could not be loaded; a failed lookup is not cached, so the next file tries again.
     * [providerHasSeason] is false when the providers reported the season as nonexistent.
     */
    context(_: LoggingContext)
    private suspend fun findViaEpisodeGroups(
        show: CanonicalMedia.TvShow,
        season: Int,
        episode: Int,
        providerHasSeason: Boolean,
    ): Result<CanonicalMedia.Episode?> = indent {
        val groups = cache.getEpisodeGroups(show.identity)
            ?: metadataService.getEpisodeGroups(show)
                .onSuccess { cache.putEpisodeGroups(show.identity, it) }
                .getOrElse { return@indent Result.failure(it) }

        val match = when (val resolution = EpisodeGroupMapper.resolve(groups, season, episode, providerHasSeason)) {
            is EpisodeGroupMapper.Resolution.Found -> {
                val mapped = resolution.episode
                if (episodeGroupNotices.add(show.identity to season)) {
                    status(
                        "Season $season of '${show.title}' mapped through episode group '${resolution.group.name}' " +
                                "(S${season.pad2()}E${episode.pad2()} → S${mapped.season.pad2()}E${mapped.episode.pad2()})"
                    )
                }
                debug("Mapped S${season}E${episode} → S${mapped.season}E${mapped.episode} via '${resolution.group.name}' part '${resolution.part.name}'")
                mapped
            }

            is EpisodeGroupMapper.Resolution.Ambiguous -> {
                if (episodeGroupNotices.add(show.identity to season)) {
                    val names = resolution.groups.joinToString(", ") { "'${it.name}'" }
                    status(
                        "Season $season of '${show.title}' is numbered differently by episode groups $names; " +
                                "use -s/--season and --episode-offset to place it."
                    )
                }
                null
            }

            EpisodeGroupMapper.Resolution.NotFound -> {
                debug("No episode group places S${season}E${episode} (${groups.size} group(s) checked).")
                null
            }
        }
        Result.success(match)
    }

    /** Fills the winning movie's IMDb ID, which search results don't carry. See [findExternalIds]. */
    context(_: LoggingContext)
    private suspend fun CanonicalMedia.Movie.withExternalIds(): Result<CanonicalMedia.Movie> {
        if (imdbId != null) return Result.success(this)
        return findExternalIds(this, tmdbId).map { ids -> ids?.let { copy(imdbId = it.imdbId) } ?: this }
    }

    /** Fills the winning show's IMDb ID, which search results don't carry. See [findExternalIds]. */
    context(_: LoggingContext)
    private suspend fun CanonicalMedia.TvShow.withExternalIds(): Result<CanonicalMedia.TvShow> {
        if (imdbId != null) return Result.success(this)
        return findExternalIds(this, tmdbId).map { ids -> ids?.let { copy(imdbId = it.imdbId) } ?: this }
    }

    /**
     * Looked up once per TMDB record and run (the service skips it when the template doesn't use
     * `{imdbid}`), so a season of episodes or several versions of a movie cost one call. A failed
     * lookup fails the file, as a failed season fetch does, and is tried again for the next one:
     * named without the ID, the file would land outside the folder the next run files the title in.
     */
    context(_: LoggingContext)
    private suspend fun findExternalIds(media: CanonicalMedia, tmdbId: String?): Result<ExternalIds?> {
        if (tmdbId == null) return Result.success(null)
        val kind = if (media is CanonicalMedia.Movie) "movie" else "tv"
        return cache.getOrPutExternalIds("$kind:$tmdbId") { metadataService.getExternalIds(media) }
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

    context(_: LoggingContext)
    internal fun findAndConsolidateBestMatch(
        results: List<MediaSearchResult>,
        parsedTitle: String,
        parsedYear: String?
    ): CanonicalMedia? = rankMatches(results, parsedTitle, parsedYear).firstOrNull()?.media

    /**
     * Every candidate scoring at least [MatchScoring.MINIMUM_CONFIDENCE_SCORE] (see [MatchScoring]
     * for the score), best first, each consolidated across providers, with its title similarity and
     * year score. Empty when none qualifies, with the reason reported unless [reportMiss] is false
     * (a ranking that a second search may redo).
     */
    context(_: LoggingContext)
    internal fun rankMatches(
        results: List<MediaSearchResult>,
        parsedTitle: String,
        parsedYear: String?,
        reportMiss: Boolean = true,
    ): List<RankedMatch> = indent {
        if (results.isEmpty()) return@indent emptyList()

        debug("Consolidating ${results.size} results for title: '$parsedTitle' year: '$parsedYear'")

        val groupedByMedia = results.groupBy { it.candidateKey }
        debug("${groupedByMedia.size} unique media candidates found.")

        // Collected so a failed lookup can report *why* in the concise output.
        val yearRejected = mutableListOf<String>()
        val yearMismatched = mutableListOf<String>()

        val scoredGroups = groupedByMedia.values.mapNotNull { group ->
            val representative = group.first()

            // A year explicitly provided by the user is a hard filter, not a scoring signal:
            // discard candidates whose known year differs. Candidates without a year are kept,
            // since there is nothing to verify against.
            val overrideYear = yearOverride?.toIntOrNull()
            val candidateYear = representative.year?.toIntOrNull()
            if (overrideYear != null && candidateYear != null && candidateYear != overrideYear) {
                debug("Candidate: '${representative.title} (${representative.year})' | Discarded (year does not match override $overrideYear)")
                yearRejected += "'${representative.title} (${representative.year})'"
                return@mapNotNull null
            }

            val normalizedParsedTitle = parsedTitle.normalizedTitle()

            // Score against every title the group is known by (display, original-language and
            // alternative titles), so a release named with a romaji or localized alias still
            // matches its canonical record.
            val titleScores = group.flatMap { it.allTitles }.distinct()
                .map { candidateTitle -> candidateTitle to titleSimilarity(normalizedParsedTitle, candidateTitle.normalizedTitle()) }
            val (bestTitle, similarity) = titleScores.maxBy { (_, similarity) -> similarity }
            val matchedVia = if (bestTitle != representative.title) " (matched via '${bestTitle}')" else ""

            var score = 0.0

            if (similarity < MatchScoring.MIN_TITLE_SIMILARITY) {
                debug("Candidate: '${representative.title} (${representative.year})' | Discarded (title similarity too low)")
                return@mapNotNull null
            }

            score += similarity * MatchScoring.TITLE_WEIGHT

            val parsedY = parsedYear?.toIntOrNull()
            val groupY = representative.year?.toIntOrNull()
            val yearScore = if (parsedY != null && groupY != null) {
                yearScore(parsedY, groupY, isShow = representative is MediaSearchResult.TvShow)
            } else null
            // A known year that contradicts the filename's makes it another film or show of the same
            // title (a remake, a namesake), however well the title matches.
            if (yearScore != null && yearScore < 0) {
                debug("Candidate: '${representative.title} (${representative.year})' | Discarded (year does not match $parsedY)")
                yearMismatched += "'${representative.title} (${representative.year})'"
                return@mapNotNull null
            }
            score += yearScore ?: 0.0

            score += (group.distinctBy { it.provider }.size - 1) * MatchScoring.PROVIDER_AGREEMENT_BONUS

            // Bonus based on the average confidence score reported by the providers
            val avgProviderConfidence = group.map { it.matchConfidence }.average()
            score += avgProviderConfidence / MatchScoring.PROVIDER_CONFIDENCE_DIVISOR

            debug("Candidate: '${representative.title} (${representative.year})' | Score: ${score.format(2)}$matchedVia")
            val titleSeason = seasonNamedBy(group, titleScores)
            ScoredGroup(group, score, similarity, yearScore, titleSeason, titleSeason == null && namesUnplacedArc(parsedTitle, group))
        }

        val bestGroup = scoredGroups.maxByOrNull { it.score }
        if (bestGroup == null || bestGroup.score < MatchScoring.MINIMUM_CONFIDENCE_SCORE) {
            val reason = when {
                yearRejected.isNotEmpty() -> "${yearRejected.listed()} rejected (year does not match override $yearOverride)"
                yearMismatched.isNotEmpty() -> "${yearMismatched.listed()} rejected (year does not match $parsedYear)"

                bestGroup != null -> {
                    val candidate = bestGroup.group.first()
                    "best candidate '${candidate.title} (${candidate.year})' scored ${bestGroup.score.format(2)}, " +
                            "below the ${MatchScoring.MINIMUM_CONFIDENCE_SCORE} confidence minimum"
                }

                else -> "none of ${results.size} provider result(s) resembled the title"
            }
            if (reportMiss) status("No match for '$parsedTitle': $reason") else debug("No match yet: $reason")
            return@indent emptyList()
        }

        debug("Best match selected: '${bestGroup.group.first().title}' with score ${bestGroup.score.format(2)}")

        // sortedByDescending is stable, so the best stays the one maxByOrNull picks on a tie.
        return@indent scoredGroups
            .filter { it.score >= MatchScoring.MINIMUM_CONFIDENCE_SCORE }
            .sortedByDescending { it.score }
            .map { RankedMatch(consolidate(it.group, parsedYear), it.similarity, it.yearScore, it.titleSeason, it.namesUnplacedArc, it.score) }
    }

    private class ScoredGroup(
        val group: List<MediaSearchResult>,
        val score: Double,
        val similarity: Double,
        val yearScore: Double?,
        val titleSeason: TitleSeason?,
        val namesUnplacedArc: Boolean,
    )

    /**
     * The season the searched title names: when it resembles one season's own title (see
     * [MediaSearchResult.TvShow.seasonTitles]) more than any title of the whole show. A
     * release named after the show itself, or equally after two seasons, names none.
     */
    private fun seasonNamedBy(group: List<MediaSearchResult>, titleScores: List<Pair<String, Double>>): TitleSeason? {
        val seasonTitles = group.filterIsInstance<MediaSearchResult.TvShow>()
            .flatMap { it.seasonTitles.entries }
            .associate { it.key to it.value }
        val (seasonScores, showScores) = titleScores.partition { (title, _) -> title in seasonTitles }
        val best = seasonScores.maxOfOrNull { it.second } ?: return null
        if (best <= (showScores.maxOfOrNull { it.second } ?: 0.0)) return null

        val named = seasonScores.filter { it.second == best }
        val season = named.map { seasonTitles.getValue(it.first) }.distinct().singleOrNull() ?: return null
        return TitleSeason(season, named.first().first)
    }

    /**
     * True when the searched title ends in an arc ("Yuukaku-hen", "Entertainment District Arc",
     * "遊郭編") and the candidate has no season title that places it. Such a release is a later arc
     * whose season the provider doesn't tag, and the season-1 default would put its episodes over
     * season 1's. A show whose own title ends that way (Noah's Arc) is not an arc.
     */
    private fun namesUnplacedArc(parsedTitle: String, group: List<MediaSearchResult>): Boolean {
        if (!ARC_SUFFIX.containsMatchIn(parsedTitle.trim())) return false
        val seasonTitles = group.filterIsInstance<MediaSearchResult.TvShow>().flatMap { it.seasonTitles.keys }.toSet()
        val showTitles = group.flatMap { it.allTitles }.filter { it !in seasonTitles }
        return showTitles.none { title -> ARC_ENDINGS.any { title.normalizedTitle().endsWith(it) } }
    }

    /**
     * The candidate a result is about: its TMDB ID, which another provider's result for the same title
     * carries too, else its IMDb ID, else the provider's own record. Never the title alone: distinct
     * titles look alike once folded (".hack" and "Hack", untranslated non-Latin titles), and a merged
     * group is scored over all its titles but named by one of them.
     */
    private val MediaSearchResult.candidateKey: String
        get() {
            val type = when (this) {
                is MediaSearchResult.Movie -> "movie"
                is MediaSearchResult.TvShow -> "tv"
            }
            return tmdbId?.let { "$type:tmdb:$it" }
                ?: imdbId?.let { "$type:imdb:$it" }
                ?: "$type:$provider:$title:$year"
        }

    /** Merges one candidate's results across providers into a golden record with all their IDs. */
    private fun consolidate(groupItems: List<MediaSearchResult>, parsedYear: String?): CanonicalMedia {
        val bestItem = groupItems.firstOrNull { it.year == parsedYear } ?: groupItems.first()

        val imdbId = groupItems.firstNotNullOfOrNull { it.imdbId }
        val tmdbId = groupItems.firstNotNullOfOrNull { it.tmdbId }

        return when (bestItem) {
            is MediaSearchResult.Movie -> CanonicalMedia.Movie(
                title = bestItem.title,
                year = bestItem.year?.toIntOrNull(),
                imdbId = imdbId,
                tmdbId = tmdbId,
            )

            is MediaSearchResult.TvShow -> CanonicalMedia.TvShow(
                title = bestItem.title,
                year = bestItem.year?.toIntOrNull(),
                imdbId = imdbId,
                tmdbId = tmdbId,
            )
        }
    }
}

// Short human-readable labels for the concise log lines.
private fun CanonicalMedia.describe(): String = when (this) {
    is CanonicalMedia.Movie -> "$title${year.inParens()}"
    is CanonicalMedia.Episode -> "S${season.pad2()}E${episode.pad2()}${lastEpisode?.let { "-E${it.pad2()}" } ?: ""} - $title"
    is CanonicalMedia.TvShow -> buildString {
        append("$title${year.inParens()}")
        tmdbId?.let { append(" [tmdbid-$it]") }
        imdbId?.let { append(" {imdb-$it}") }
    }
}

private fun Int?.inParens(): String = this?.let { " ($it)" } ?: ""

// The first few rejected candidates for a "No match" line, with a count of the rest.
private fun List<String>.listed(): String =
    take(3).joinToString(", ") + if (size > 3) " and ${size - 3} more" else ""

private fun Int.pad2(): String = toString().padStart(2, '0')

// Helper function to format a Double to a specific number of decimal places in a multiplatform-safe way.
private fun Double.format(digits: Int): String {
    val factor = 10.0.pow(digits)
    if (this.isNaN() || this.isInfinite()) {
        return this.toString()
    }
    val scaled = (this * factor).roundToInt()
    val intPart = scaled / factor.toInt()
    val fracPart = scaled.mod(factor.toInt())
    return "$intPart.${fracPart.toString().padStart(digits, '0')}"
}

/**
 * Year contribution to a candidate's match score (values in [MatchScoring]).
 *
 * Movies: exact [MatchScoring.EXACT_YEAR_SCORE] (+10), adjacent [MatchScoring.ADJACENT_YEAR_SCORE]
 * (+5), otherwise [MatchScoring.YEAR_MISMATCH_SCORE] (-10).
 *
 * Shows: a show's year is its first-air year, but the year in an episode filename is usually the
 * season's air year (`The_Boys_S03E01_2022` is The Boys (2019)). A show that started before the
 * filename year is therefore consistent with it, not a mismatch: it scores +5, slowly decaying with
 * distance (0.1 a year, to at least +1) so the closest earlier show wins among same-titled ones. Only
 * a show that started after the filename year (beyond the usual one-year release-date disagreement)
 * is penalized.
 *
 * A negative score is a contradiction, not a weak signal: [MediaProcessor.rankMatches] discards the candidate.
 */
internal fun yearScore(parsedYear: Int, candidateYear: Int, isShow: Boolean): Double {
    val diff = parsedYear - candidateYear
    return when {
        diff == 0 -> MatchScoring.EXACT_YEAR_SCORE
        abs(diff) == 1 -> MatchScoring.ADJACENT_YEAR_SCORE
        isShow && diff > 1 -> max(MatchScoring.SHOW_YEAR_FLOOR, MatchScoring.ADJACENT_YEAR_SCORE - (diff - 1) * MatchScoring.SHOW_YEAR_DECAY)
        else -> MatchScoring.YEAR_MISMATCH_SCORE
    }
}

// An arc at the end of a release title: romaji "-hen" (separators are spaces by then), English
// "Arc", Japanese/Chinese 編/篇.
private val ARC_SUFFIX = Regex("""(?:\s(?:hen|arc)|[編篇])$""", RegexOption.IGNORE_CASE)
private val ARC_ENDINGS = listOf("hen", "arc", "編", "篇")

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
