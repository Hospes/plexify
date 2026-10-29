package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.matching.MatchRanker
import io.github.hospes.plexify.domain.matching.RankedMatch
import io.github.hospes.plexify.domain.matching.awaitingAliases
import io.github.hospes.plexify.domain.matching.calculateTitleConfidence
import io.github.hospes.plexify.domain.matching.consolidate
import io.github.hospes.plexify.domain.matching.format
import io.github.hospes.plexify.domain.matching.yearScore
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaId
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.model.withOverrides
import io.github.hospes.plexify.domain.service.MediaFilenameParser
import io.github.hospes.plexify.domain.service.isNamedById
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

    private val ranker = MatchRanker(yearOverride)

    // What the run decided about each show key: the season its title names, its release seasons' shows, notices.
    private val showStates = ShowStates()

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
        val first = searchFor<MediaSearchResult.Movie>(title, year) { metadata.search(title, year) }
            .getOrElse { return Result.failure(it) }
        val firstRanking = rankMatches(first, title, year, reportMiss = year == null)
        if (year == null || firstRanking.bestYear() == year) return Result.success(first to firstRanking)

        // A search that fails after an earlier one found a candidate is left out; with no candidate
        // yet, it decides the file.
        suspend fun moviesUnlessFailed(found: List<RankedMatch>, search: suspend () -> Result<List<MediaSearchResult.Movie>>) =
            search().fold(
                onSuccess = { Result.success(it) },
                onFailure = { error ->
                    if (found.isEmpty()) return@fold Result.failure(error)
                    debug("Search failed: ${error.message}")
                    Result.success(emptyList())
                },
            )

        debug("No match from $year among the first results; searching again with the year")
        val byYear = moviesUnlessFailed(firstRanking) {
            searchFor<MediaSearchResult.Movie>(title, year) { metadata.searchMovies(title, year) }
        }
            .getOrElse { return Result.failure(it) }
        val merged = (first + byYear).distinctBy { listOf(it.provider, it.tmdbId ?: "${it.title}:${it.year}") }
        val ranking = rankMatches(merged, title, year, reportMiss = false)

        if (ranking.bestYear() != year && titleOverride == null && yearOverride == null) {
            val joinedTitle = "$title $year"
            // Only a film whose title has the year: the reading has no year to rule out the namesakes
            // the first one rejected ("Dracula" for "Dracula 1974").
            val namesYear = { media: CanonicalMedia -> year in (media as CanonicalMedia.Movie).title }
            debug("Still no match from $year; trying '$joinedTitle' as the whole title")
            val joined = moviesUnlessFailed(ranking) {
                searchFor<MediaSearchResult.Movie>(joinedTitle, null, namesYear) { metadata.search(joinedTitle, null) }
            }
                .getOrElse { return Result.failure(it) }
            val joinedRanking = rankMatches(joined, joinedTitle, null, reportMiss = false).filter { namesYear(it.media) }
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

        // Step 2: Find the season and the episode. A show found by its ID is keyed by the ID too, as two
        // shows may share their cleaned titles.
        val showKey = byId?.first ?: searchedKey
        val resolution = episodeResolver.resolve(source, parsedInfo, candidates, showKey)
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
        // With its external IDs now, so the season cached below carries them, as EpisodeResolver expects.
        val show = bestShow(numbered.showTitle, parsed.year).getOrElse { return@indent Result.failure(it) }
            ?.let { metadata.withImdbId(it) }?.getOrElse { return@indent Result.failure(it) }
            ?: return@indent Result.success(null)
        val season = metadata.season(show, numbered.season).getOrElse { return@indent Result.failure(it) }
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
        searchFor<MediaSearchResult.TvShow>(title, year) { metadata.search(title, year) }.map { results ->
            rankMatches(results, title, year, reportMiss = false)
                .firstOrNull()?.media as? CanonicalMedia.TvShow
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
        val cachedShows = metadata.cachedShows(key)
        if (cachedShows != null) {
            debug(if (cachedShows.isEmpty()) "Cache HIT (negative) for show: '$title'" else "Cache HIT for show: '$title'")
            return@indent Result.success(cachedShows)
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
            // fallback picks it (see EpisodeResolver). A failed lookup fails before anything is kept.
            val bestShow = best?.let { metadata.withImdbId(it.media as CanonicalMedia.TvShow).getOrElse { error -> return@indent Result.failure(error) } }
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
            metadata.putShows(key, shows)
            status("Matched show: ${shows.first().describe()}")
        } else {
            // Negative cache: don't repeat the search (and its log output) for every episode file.
            metadata.markShowUnmatched(key)
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
        val record = metadata.findMovie(id).getOrElse { return@indent Result.failure(it) }
        val movie = (rankById(id, record, "movie", parsed.title, parsed.year)?.media as? CanonicalMedia.Movie)
            ?.let { metadata.withImdbId(it) }?.getOrElse { return@indent Result.failure(it) }
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
        metadata.cachedShows(key)?.let { return@indent Result.success(it.firstOrNull()?.let { show -> key to show }) }

        debug("Looking up the show $id in the path")
        val record = metadata.findShow(id).getOrElse { return@indent Result.failure(it) }
        val match = rankById(id, record, "show", parsed.showTitle, parsed.year)
        val show = (match?.media as? CanonicalMedia.TvShow)?.let { metadata.withImdbId(it) }?.getOrElse { return@indent Result.failure(it) }
        if (match == null || show == null) {
            metadata.markShowUnmatched(key)
        } else {
            val state = showStates[key]
            state.titleSeason = match.titleSeason
            if (match.namesUnplacedArc) state.namesUnplacedArc = true
            metadata.putShows(key, listOf(show))
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
        val found = searchFor<MediaSearchResult.TvShow>(title, year) { metadata.search(title, year) }
            .getOrElse { return Result.failure(it) }
        val titleYear = year?.takeIf { bareYear && titleOverride == null && yearOverride == null }
        val ranking = rankMatches(found, title, year, reportMiss = titleYear == null).filter { it.media is CanonicalMedia.TvShow }
        if (titleYear == null || (ranking.firstOrNull()?.media as? CanonicalMedia.TvShow)?.year?.toString() == titleYear) {
            return Result.success(found to ranking)
        }

        val joinedTitle = "$title $titleYear"
        // Only a show whose title has the year: the reading has no year to rule out other shows.
        val namesYear = { media: CanonicalMedia -> (media as? CanonicalMedia.TvShow)?.title?.contains(titleYear) == true }
        debug("No show from $titleYear matched; trying '$joinedTitle' as the whole title")
        val joined = searchFor<MediaSearchResult.TvShow>(joinedTitle, null, namesYear) { metadata.search(joinedTitle, null) }.fold(
            onSuccess = { it },
            onFailure = { error ->
                // With no candidate yet, the failed search decides the file.
                if (ranking.isEmpty()) return Result.failure(error)
                debug("Search failed: ${error.message}")
                emptyList()
            },
        )
        val joinedRanking = rankMatches(joined, joinedTitle, null, reportMiss = false).filter { namesYear(it.media) }
        val joinedScore = joinedRanking.firstOrNull()?.score
        if (joinedScore != null && joinedScore > (ranking.firstOrNull()?.score ?: 0.0)) {
            debug("'$joinedTitle' matches better (${joinedScore.format(2)})")
            return Result.success(found + joined to joinedRanking)
        }
        if (ranking.isEmpty()) rankMatches(found, title, year) // reports why nothing matched
        return Result.success(found + joined to ranking)
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

    /**
     * The [T] results of [search] for [title] and [year], with their aliases looked up only when they could change the
     * best match the caller takes among those [accepts] keeps ([MatchRanker.aliasesCanChangeWinner]). The lookups are
     * those the provider always made, for the same results with every kind counted ([awaitingAliases]), with the same
     * failure rule ([MetadataRepository.withAlternativeTitles]). Fails when the search or the lookups failed.
     */
    context(_: LoggingContext)
    private suspend inline fun <reified T : MediaSearchResult> searchFor(
        title: String,
        year: String?,
        noinline accepts: (CanonicalMedia) -> Boolean = { true },
        search: () -> Result<List<MediaSearchResult>>,
    ): Result<List<T>> {
        val results = search().getOrElse { return Result.failure(it) }
        val ofKind = results.filterIsInstance<T>()
        if (!ranker.aliasesCanChangeWinner(ofKind, results.awaitingAliases(), title, year, accepts)) return Result.success(ofKind)
        indent { debug("Alternative titles may change the match for '$title'; looking them up") }
        return metadata.withAlternativeTitles(results, title).map { it.filterIsInstance<T>() }
    }

    /**
     * The [MatchRanker] ranking of [results], with how each candidate scored printed in verbose mode. When none
     * qualifies, the reason is reported unless [reportMiss] is false (a ranking that a second search may redo).
     */
    context(_: LoggingContext)
    private fun rankMatches(
        results: List<MediaSearchResult>,
        parsedTitle: String,
        parsedYear: String?,
        reportMiss: Boolean = true,
    ): List<RankedMatch> = indent {
        val ranking = ranker.rank(results, parsedTitle, parsedYear)
        ranking.trace.forEach { debug(it) }
        ranking.miss?.let { reason ->
            if (reportMiss) status("No match for '$parsedTitle': $reason") else debug("No match yet: $reason")
        }
        ranking.matches
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
