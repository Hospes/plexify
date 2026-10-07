package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.MetadataCache
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.model.withOverrides
import io.github.hospes.plexify.domain.service.EpisodeGroupMapper
import io.github.hospes.plexify.domain.service.MediaFilenameParser
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.logging.LoggingContext
import io.github.hospes.plexify.logging.debug
import io.github.hospes.plexify.logging.indent
import io.github.hospes.plexify.logging.log
import io.github.hospes.plexify.logging.status
import kotlinx.io.files.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
    private val MINIMUM_CONFIDENCE_SCORE = 5.0 // A score below this is considered a poor match.
    private val MAX_FALLBACK_SHOWS = 3 // Runner-up shows tried when the best match lacks a season.

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
    )

    // Directories already warned about missing season numbers, to avoid repeating the
    // warning for every episode file of the same release.
    private val seasonWarnedDirs = mutableSetOf<String>()

    // Show+season pairs already reported as mapped (or unmappable) through episode groups.
    private val episodeGroupNotices = mutableSetOf<String>()

    // The show each release season (keyed "title:year:season") was placed in, so later files of the
    // same season go to the same show instead of being matched again.
    private val seasonShows = mutableMapOf<String, CanonicalMedia.TvShow>()

    // Seasons that could not be fetched (network error, rate limit), as opposed to ones the provider
    // reported as nonexistent. Only the latter is evidence that a show is the wrong match.
    private val unavailableSeasons = mutableSetOf<String>()

    context(_: LoggingContext)
    suspend fun process(source: Path, destination: Path, mode: OperationMode, isTestMode: Boolean) {
        val kind = try {
            PlatformFileSystem.kind(source)
        } catch (e: Exception) {
            log("Error: ${e.message}")
            return
        }
        if (kind == null) {
            log("Error: Source path does not exist: $source")
            return
        }

        if (kind == FileKind.DIRECTORY) {
            val mediaFiles = walkFiles(source) { path, error ->
                log("Warning: Skipping unreadable $path (${error.message})")
                stats.skipped++
            }
                .filter { it.name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS }
                .toList()

            if (mediaFiles.isEmpty()) {
                log("No supported media files found in: $source")
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
            log("Warning: Source is not a regular file or directory, skipping: $source")
        }
    }

    context(_: LoggingContext)
    private suspend fun processFile(source: Path, destination: Path, mode: OperationMode, isTestMode: Boolean) = indent {
        debug("Processing: $source")
        val parentDirName = source.parent?.name
        when (val parsedInfo = MediaFilenameParser.parse(source.name, parentDirName).withOverrides(titleOverride, seasonOverride, yearOverride, episodeOffset)) {
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

        val searchResults = metadataService.search(parsedInfo.title, parsedInfo.year)
            .filterIsInstance<MediaSearchResult.Movie>()

        if (searchResults.isEmpty()) {
            status("✗ ${source.name} — no metadata found for '${parsedInfo.title}'")
            stats.skipped++
            return@indent
        }

        val canonicalMovie = (findAndConsolidateBestMatch(searchResults, parsedInfo.title, parsedInfo.year)
                as? CanonicalMedia.Movie)
            ?.withExternalIds()

        if (canonicalMovie == null) {
            status("✗ ${source.name} — no confident match for '${parsedInfo.title}'")
            stats.skipped++
            return@indent
        }

        debug("Found match: $canonicalMovie")
        organizeFile(source, destination, canonicalMovie, parsedInfo, mode, isTestMode)
    }

    context(_: LoggingContext)
    private suspend fun processEpisode(
        source: Path,
        destination: Path,
        mode: OperationMode,
        parsedInfo: ParsedMediaInfo.Episode,
        isTestMode: Boolean,
    ) = indent {
        val season = parsedInfo.season ?: run {
            if (seasonWarnedDirs.add(source.parent?.toString() ?: source.name)) {
                status("Warning: No season number found in filenames, defaulting to Season 1 (use -s/--season to set it explicitly).")
            }
            1
        }
        debug("Parsed as TV Show: Show='${parsedInfo.showTitle}', Season: $season, Episode: ${parsedInfo.episode}")

        // Step 1: Find the candidate shows, best first, using the cache first.
        val candidates = findOrFetchShows(parsedInfo.showTitle, parsedInfo.year)
        if (candidates.isEmpty()) {
            status("✗ ${source.name} — no confident match for show '${parsedInfo.showTitle}'")
            stats.skipped++
            return@indent
        }
        debug("Found show: ${candidates.first()}")

        // Step 2: Find the episode details via season-level fetch (fills whole season cache in one call).
        val seasonKey = "${parsedInfo.showTitle}:${parsedInfo.year}:$season"
        val firstEpisodeMatch = findEpisode(candidates, seasonKey, season, parsedInfo.episode)
        if (firstEpisodeMatch == null) {
            status("✗ ${source.name} — episode S${season}E${parsedInfo.episode} not found")
            stats.skipped++
            return@indent
        }
        val bestEpisodeMatch = parsedInfo.lastEpisode
            ?.let { last -> extendToRange(firstEpisodeMatch, candidates, seasonKey, season, parsedInfo.episode, last, source.name) }
            ?: firstEpisodeMatch

        debug("Found episode: ${bestEpisodeMatch.describe()}")
        organizeFile(source, destination, bestEpisodeMatch, parsedInfo, mode, isTestMode)
    }

    /**
     * Extends the first episode of a multi-episode file ("S01E01-E03") to its whole range. Every other
     * episode is looked up like the first (same show, cached season, episode groups); the range is kept
     * only when they land on consecutive episodes of one provider season, else the file is filed as its
     * first episode.
     */
    context(_: LoggingContext)
    private suspend fun extendToRange(
        first: CanonicalMedia.Episode,
        candidates: List<CanonicalMedia.TvShow>,
        seasonKey: String,
        season: Int,
        firstEpisode: Int,
        lastEpisode: Int,
        fileName: String,
    ): CanonicalMedia.Episode {
        val episodes = mutableListOf(first)
        for (episode in (firstEpisode + 1)..lastEpisode) {
            val match = findEpisode(candidates, seasonKey, season, episode)
            val previous = episodes.last()
            if (match == null || match.season != previous.season || match.episode != previous.episode + 1) {
                status(
                    "Warning: ${fileName} — S${season.pad2()}E${episode.pad2()} is not the episode after " +
                            "S${previous.season.pad2()}E${previous.episode.pad2()} in '${first.show.title}'; " +
                            "filing it as S${first.season.pad2()}E${first.episode.pad2()} only."
                )
                return first
            }
            episodes += match
        }
        return first.copy(
            lastEpisode = episodes.last().episode,
            title = episodes.map { it.title }.distinct().joinToString(" & "),
        )
    }

    /**
     * The shows matching a title, best first (empty when none is confident), checking the cache
     * before searching providers. Only the best is reported; the rest, same-titled shows only
     * (see [canStandInFor]), back up the episode lookup.
     */
    context(_: LoggingContext)
    private suspend fun findOrFetchShows(title: String, year: String?): List<CanonicalMedia.TvShow> = indent {
        val cacheKey = "$title:$year"
        val cachedShows = cache.getShows(cacheKey)
        if (cachedShows != null) {
            debug("Cache HIT for show: '$title'")
            return@indent cachedShows
        }
        if (cache.isShowFailed(cacheKey)) {
            debug("Cache HIT (negative) for show: '$title'")
            return@indent emptyList()
        }
        debug("Cache MISS for show: '$title'. Searching providers...")

        val searchResults = metadataService.search(title, year)
            .filterIsInstance<MediaSearchResult.TvShow>()

        val shows = if (searchResults.isEmpty()) {
            status("No match for '$title': providers returned no results.")
            emptyList()
        } else {
            val ranked = rankMatches(searchResults, title, year).filter { it.media is CanonicalMedia.TvShow }
            val best = ranked.firstOrNull()
            val runnerUps = ranked.drop(1).filter { candidate ->
                (best != null && candidate.canStandInFor(best)).also { sameShow ->
                    if (!sameShow) debug("Not a fallback for '$title': ${candidate.media.describe()} (title or year differs)")
                }
            }
            // Only the best match gets its external IDs now; a runner-up gets them if the
            // episode fallback picks it (see findEpisode).
            listOfNotNull(best).map { (it.media as CanonicalMedia.TvShow).withExternalIds() } +
                    runnerUps.map { it.media as CanonicalMedia.TvShow }
        }

        if (shows.isNotEmpty()) {
            cache.putShows(cacheKey, shows)
            status("Matched show: ${shows.first().describe()}")
        } else {
            // Negative cache: don't repeat the search (and its log output) for every episode file.
            cache.markShowFailed(cacheKey)
        }

        return@indent shows
    }

    /**
     * Finds the episode in the best-matching show. When that show has no such season at all, it is
     * not the release's show (e.g. a same-titled spin-off with one season), so the same-titled
     * runner-up shows are tried and the first that has the exact episode is used. The show that places a season is
     * kept for the rest of that season's files, so one season never splits across shows.
     */
    context(_: LoggingContext)
    private suspend fun findEpisode(
        candidates: List<CanonicalMedia.TvShow>,
        seasonKey: String,
        season: Int,
        episode: Int,
    ): CanonicalMedia.Episode? {
        // A runner-up's season was fetched before its external IDs were, so its cached episodes
        // carry the show without them: hand out the season's show as decided.
        seasonShows[seasonKey]?.let { show -> return findOrFetchEpisode(show, season, episode)?.copy(show = show) }

        val primary = candidates.first()
        findOrFetchEpisode(primary, season, episode)?.let { match ->
            seasonShows[seasonKey] = primary
            return match
        }

        // The show has the season, just not this episode (or the season couldn't be fetched):
        // a numbering quirk or an outage, not evidence of a wrong show.
        if (!isSeasonMissing(primary, season)) return null

        for (candidate in candidates.drop(1).take(MAX_FALLBACK_SHOWS)) {
            val match = indent { findOrFetchSeason(candidate, season) }
                .episodes.firstOrNull { it.episode == episode } ?: continue
            val alternative = candidate.withExternalIds()
            seasonShows[seasonKey] = alternative
            status("Season $season is not in ${primary.describe()}; matched show: ${alternative.describe()}")
            return match.copy(show = alternative)
        }
        return null
    }

    /**
     * Fetches the whole season (one API call) and returns the requested episode.
     * Subsequent episodes from the same season are served from cache.
     */
    context(_: LoggingContext)
    private suspend fun findOrFetchEpisode(show: CanonicalMedia.TvShow, season: Int, episode: Int): CanonicalMedia.Episode? = indent {
        val cachedSeason = findOrFetchSeason(show, season)

        val match = cachedSeason.episodes.firstOrNull { it.episode == episode }
        if (match != null) return@indent match

        debug("Episode E${episode} not found in S${season} data. Trying episode groups...")
        findViaEpisodeGroups(show, season, episode)
    }

    /** The whole season, fetched once per show and season; empty when it is missing or failed to load. */
    context(_: LoggingContext)
    private suspend fun findOrFetchSeason(show: CanonicalMedia.TvShow, season: Int): CanonicalMedia.Season {
        val cacheKey = show.seasonCacheKey(season)

        val cachedSeason = cache.getSeason(cacheKey) ?: run {
            debug("Cache MISS for season: S${season}. Fetching from providers...")
            val seasonData = metadataService.getSeason(show, season)
                // Cache the failure as an empty season so the remaining files of this season
                // don't re-query the providers and re-log the same error.
                ?: CanonicalMedia.Season(show, season, emptyList()).also {
                    unavailableSeasons += cacheKey
                    debug("Season $season of '${show.title}' is not available from providers.")
                }
            cache.putSeason(cacheKey, seasonData)
            seasonData
        }

        if (cachedSeason.episodes.isNotEmpty()) {
            debug("Cache HIT for S${season} (${cachedSeason.episodes.size} episodes loaded)")
        }
        return cachedSeason
    }

    /** True only when the providers reported the season as nonexistent, not when fetching it failed. */
    private suspend fun isSeasonMissing(show: CanonicalMedia.TvShow, season: Int): Boolean {
        val cacheKey = show.seasonCacheKey(season)
        return cacheKey !in unavailableSeasons && cache.getSeason(cacheKey)?.episodes?.isEmpty() == true
    }

    /**
     * Whether this runner-up may take a season the best match lacks: only a show named as the
     * release is, as closely as the best match (a same-titled show, like Being Human UK and US),
     * and not contradicted by the filename year. A merely similar title clears the confidence
     * minimum too, but it is a different show.
     */
    private fun RankedMatch.canStandInFor(best: RankedMatch): Boolean =
        titleSimilarity >= best.titleSimilarity && (yearScore == null || yearScore > 0)

    private fun CanonicalMedia.TvShow.seasonCacheKey(season: Int): String = "${tmdbId ?: imdbId ?: title}:$season"

    /**
     * Fallback for releases numbered differently from the provider, typically anime split into
     * cours: the release's S2E01 is TMDB's S1E13. Episode groups (fetched once per show) record
     * that split; the episode found is the provider's own, so the file gets TMDB's numbering.
     */
    context(_: LoggingContext)
    private suspend fun findViaEpisodeGroups(show: CanonicalMedia.TvShow, season: Int, episode: Int): CanonicalMedia.Episode? = indent {
        val showId = show.tmdbId ?: show.imdbId ?: show.title
        val groups = cache.getEpisodeGroups(showId)
            ?: metadataService.getEpisodeGroups(show).also { cache.putEpisodeGroups(showId, it) }

        when (val resolution = EpisodeGroupMapper.resolve(groups, season, episode)) {
            is EpisodeGroupMapper.Resolution.Found -> {
                val mapped = resolution.episode
                if (episodeGroupNotices.add("$showId:$season")) {
                    status(
                        "Season $season of '${show.title}' mapped through episode group '${resolution.group.name}' " +
                                "(S${season.pad2()}E${episode.pad2()} → S${mapped.season.pad2()}E${mapped.episode.pad2()})"
                    )
                }
                debug("Mapped S${season}E${episode} → S${mapped.season}E${mapped.episode} via '${resolution.group.name}' part '${resolution.part.name}'")
                mapped
            }

            is EpisodeGroupMapper.Resolution.Ambiguous -> {
                if (episodeGroupNotices.add("$showId:$season")) {
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
    }

    /** Fills the winning movie's IMDb ID, which search results don't carry. See [findExternalIds]. */
    context(_: LoggingContext)
    private suspend fun CanonicalMedia.Movie.withExternalIds(): CanonicalMedia.Movie {
        if (imdbId != null) return this
        val ids = findExternalIds(this, tmdbId) ?: return this
        return copy(imdbId = ids.imdbId)
    }

    /** Fills the winning show's IMDb and TVDb IDs, which search results don't carry. See [findExternalIds]. */
    context(_: LoggingContext)
    private suspend fun CanonicalMedia.TvShow.withExternalIds(): CanonicalMedia.TvShow {
        if (imdbId != null && tvdbId != null) return this
        val ids = findExternalIds(this, tmdbId) ?: return this
        return copy(imdbId = imdbId ?: ids.imdbId, tvdbId = tvdbId ?: ids.tvdbId)
    }

    /**
     * Looked up once per TMDB record and run (the service skips it when the template uses neither
     * `{imdbid}` nor `{tvdbid}`), so a season of episodes or several versions of a movie cost one call.
     */
    context(_: LoggingContext)
    private suspend fun findExternalIds(media: CanonicalMedia, tmdbId: String?): ExternalIds? {
        if (tmdbId == null) return null
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
                        status("✓ ${source.name} → ${media.describe()}")
                        debug("Organized at: ${outcome.path}")
                        stats.organized++
                    }

                    is OrganizeOutcome.Replaced -> {
                        status("✓ ${source.name} → ${media.describe()} (replaced existing file)")
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
     * Every candidate scoring at least [MINIMUM_CONFIDENCE_SCORE], best first, each consolidated
     * across providers, with its title similarity and year score. Empty, with the reason reported,
     * when none qualifies.
     */
    context(_: LoggingContext)
    internal fun rankMatches(
        results: List<MediaSearchResult>,
        parsedTitle: String,
        parsedYear: String?
    ): List<RankedMatch> = indent {
        if (results.isEmpty()) return@indent emptyList()

        debug("Consolidating ${results.size} results for title: '$parsedTitle' year: '$parsedYear'")

        val groupedByMedia = results.groupBy {
            val normalizedTitle = it.title.lowercase().replace(Regex("[^a-z0-9]"), "")
            "$normalizedTitle:${it.year}"
        }
        debug("${groupedByMedia.size} unique media candidates found.")

        // Collected so a failed lookup can report *why* in the concise output.
        val yearRejected = mutableListOf<String>()

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
            val (bestTitle, similarity) = group.flatMap { it.allTitles }.distinct()
                .map { candidateTitle -> candidateTitle to titleSimilarity(normalizedParsedTitle, candidateTitle.normalizedTitle()) }
                .maxBy { (_, similarity) -> similarity }
            val matchedVia = if (bestTitle != representative.title) " (matched via '${bestTitle}')" else ""

            var score = 0.0

            if (similarity < 0.4) {
                debug("Candidate: '${representative.title} (${representative.year})' | Discarded (title similarity too low)")
                return@mapNotNull null
            }

            score += similarity * 10.0

            val parsedY = parsedYear?.toIntOrNull()
            val groupY = representative.year?.toIntOrNull()
            val yearScore = if (parsedY != null && groupY != null) {
                yearScore(parsedY, groupY, isShow = representative is MediaSearchResult.TvShow)
            } else null
            score += yearScore ?: 0.0

            score += (group.distinctBy { it.provider }.size - 1) * 2.0

            // Bonus based on the average confidence score reported by the providers
            val avgProviderConfidence = group.map { it.matchConfidence }.average()
            // We scale it (e.g., divide by 20) to make it a bonus, not the main driver of the score
            score += avgProviderConfidence / 20.0

            debug("Candidate: '${representative.title} (${representative.year})' | Score: ${score.format(2)}$matchedVia")
            ScoredGroup(group, score, similarity, yearScore)
        }

        val bestGroup = scoredGroups.maxByOrNull { it.score }
        if (bestGroup == null || bestGroup.score < MINIMUM_CONFIDENCE_SCORE) {
            val reason = when {
                yearRejected.isNotEmpty() -> {
                    val listed = yearRejected.take(3).joinToString(", ")
                    val more = if (yearRejected.size > 3) " and ${yearRejected.size - 3} more" else ""
                    "$listed$more rejected (year does not match override $yearOverride)"
                }

                bestGroup != null -> {
                    val candidate = bestGroup.group.first()
                    "best candidate '${candidate.title} (${candidate.year})' scored ${bestGroup.score.format(2)}, " +
                            "below the $MINIMUM_CONFIDENCE_SCORE confidence minimum"
                }

                else -> "none of ${results.size} provider result(s) resembled the title"
            }
            status("No match for '$parsedTitle': $reason")
            return@indent emptyList()
        }

        debug("Best match selected: '${bestGroup.group.first().title}' with score ${bestGroup.score.format(2)}")

        // sortedByDescending is stable, so the best stays the one maxByOrNull picks on a tie.
        return@indent scoredGroups
            .filter { it.score >= MINIMUM_CONFIDENCE_SCORE }
            .sortedByDescending { it.score }
            .map { RankedMatch(consolidate(it.group, parsedYear), it.similarity, it.yearScore) }
    }

    private class ScoredGroup(
        val group: List<MediaSearchResult>,
        val score: Double,
        val similarity: Double,
        val yearScore: Double?,
    )

    /** Merges one candidate's results across providers into a golden record with all their IDs. */
    private fun consolidate(groupItems: List<MediaSearchResult>, parsedYear: String?): CanonicalMedia {
        val bestItem = groupItems.firstOrNull { it.year == parsedYear } ?: groupItems.first()

        val imdbId = groupItems.firstNotNullOfOrNull { it.imdbId }
        val tmdbId = groupItems.firstNotNullOfOrNull { it.tmdbId }
        val tvdbId = groupItems.firstNotNullOfOrNull { it.tvdbId }

        return when (bestItem) {
            is MediaSearchResult.Movie -> CanonicalMedia.Movie(
                title = bestItem.title,
                year = bestItem.year?.toIntOrNull(),
                imdbId = imdbId,
                tmdbId = tmdbId,
                tvdbId = tvdbId
            )

            is MediaSearchResult.TvShow -> CanonicalMedia.TvShow(
                title = bestItem.title,
                year = bestItem.year?.toIntOrNull(),
                imdbId = imdbId,
                tmdbId = tmdbId,
                tvdbId = tvdbId
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
        tvdbId?.let { append(" [tvdbid-$it]") }
        imdbId?.let { append(" [imdbid-$it]") }
    }
}

private fun Int?.inParens(): String = this?.let { " ($it)" } ?: ""

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
 * Year contribution to a candidate's match score.
 *
 * Movies: Exact(+10), Adjacent(+5), Mismatch(-10).
 *
 * Shows: a show's year is its first-air year, but the year in an episode filename is usually the
 * season's air year (`The_Boys_S03E01_2022` is The Boys (2019)). A show that started before the
 * filename year is therefore consistent with it, not a mismatch: it scores +5, slowly decaying with
 * distance so the closest earlier show wins among same-titled ones. Only a show that started after
 * the filename year (beyond the usual one-year release-date disagreement) is penalized.
 */
internal fun yearScore(parsedYear: Int, candidateYear: Int, isShow: Boolean): Double {
    val diff = parsedYear - candidateYear
    return when {
        diff == 0 -> 10.0
        abs(diff) == 1 -> 5.0
        isShow && diff > 1 -> max(1.0, 5.0 - (diff - 1) * 0.1)
        else -> -10.0
    }
}

// Keep only alphanumeric chars for title comparison, so "Spider-Man" matches "Spiderman".
private fun String.normalizedTitle(): String = filter { it.isLetterOrDigit() }.lowercase()

/** Normalized Levenshtein similarity in [0.0, 1.0]; 1.0 means identical strings. */
internal fun titleSimilarity(lhs: String, rhs: String): Double {
    val distance = levenshtein(lhs, rhs)
    val length = max(lhs.length, rhs.length)
    return if (length > 0) 1.0 - (distance.toDouble() / length) else 0.0
}

/**
 * Calculates the Levenshtein distance between two strings.
 * This is a measure of the number of single-character edits (insertions, deletions, or substitutions)
 * required to change one word into the other.
 */
internal fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
    val lhsLength = lhs.length
    val rhsLength = rhs.length

    var cost = Array(lhsLength + 1) { it }
    val newCost = Array(lhsLength + 1) { 0 }

    for (i in 1..rhsLength) {
        newCost[0] = i
        for (j in 1..lhsLength) {
            val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
            val costReplace = cost[j - 1] + match
            val costInsert = cost[j] + 1
            val costDelete = newCost[j - 1] + 1
            newCost[j] = min(min(costInsert, costDelete), costReplace)
        }
        cost = newCost.copyOf()
    }
    return cost[lhsLength]
}