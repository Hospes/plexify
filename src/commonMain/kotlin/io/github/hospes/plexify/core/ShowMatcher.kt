package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.matching.RankedMatch
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.logging.LoggingContext
import io.github.hospes.plexify.logging.debug
import io.github.hospes.plexify.logging.indent
import io.github.hospes.plexify.logging.status

/**
 * The shows an episode file may be in, best first: the show the ID in its path names, else the shows its title matches,
 * once the number closing the title is read as its season or as part of it. What the run decides about each show (the
 * season its title names, an arc no season title places) goes into [showStates], for [EpisodeResolver].
 */
internal class ShowMatcher(
    private val metadata: MetadataRepository,
    private val search: MatchSearch,
    private val showStates: ShowStates,
    private val titleOverride: String?,
    private val yearOverride: String?,
) {
    /**
     * [parsed], with the season the number closing its title names, if it does; the [shows] it may be in, best first
     * (empty when none matches confidently), and the [key] what the run decides about them is kept under.
     */
    class Match(val parsed: ParsedMediaInfo.Episode, val shows: List<CanonicalMedia.TvShow>, val key: ShowKey)

    /** The [Match] for [parsed]. Fails when a lookup failed: that says nothing about the show, so the file is a failure. */
    context(_: LoggingContext)
    suspend fun match(parsed: ParsedMediaInfo.Episode): Result<Match> {
        val parsedInfo = withSeasonFromTitle(parsed).getOrElse { return Result.failure(it) }

        // The show the path's ID names is the only one: it has no runner-ups to take a season it lacks. It is kept under
        // its ID too, as two shows may share their cleaned titles.
        val byId = findShowById(parsedInfo).getOrElse { return Result.failure(it) }
        if (byId != null) return Result.success(Match(parsedInfo, listOf(byId.second), byId.first))

        val key = ShowKey.Searched(parsedInfo.showTitle, parsedInfo.year)
        return findOrFetchShows(key, parsedInfo.bareShowYear).map { shows -> Match(parsedInfo, shows, key) }
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
        search.searchFor<MediaSearchResult.TvShow>(title, year) { metadata.search(title, year) }.map { results ->
            search.rank(results, title, year, reportMiss = false).matches
                .firstOrNull()?.media as? CanonicalMedia.TvShow
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
     * The show the ID in the path names, with its external IDs and the [ShowKey.ById] it is kept under, looked up once
     * per ID, title and year; null when the path names none or it isn't this file's show (see [MatchSearch.rankById]),
     * so the title is searched. The season its title names (an anime arc) is kept under that key, as [findOrFetchShows]
     * keeps a searched show's. A failed lookup is not cached, so the next file of the show tries again.
     */
    context(_: LoggingContext)
    private suspend fun findShowById(parsed: ParsedMediaInfo.Episode): Result<Pair<ShowKey.ById, CanonicalMedia.TvShow>?> = indent {
        val id = parsed.mediaId ?: return@indent Result.success(null)
        val key = ShowKey.ById(id, parsed.showTitle, parsed.year)
        metadata.cachedShows(key)?.let { return@indent Result.success(it.firstOrNull()?.let { show -> key to show }) }

        debug("Looking up the show $id in the path")
        val record = metadata.findShow(id).getOrElse { return@indent Result.failure(it) }
        val match = search.rankById(id, record, "show", parsed.showTitle, parsed.year)
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
     * Every show result found for [title] and [year], with the ranked candidates. A [bareYear] closed the
     * show name without brackets, so unless the user set the title or the year, it may belong to the title:
     * when no show from that year matches, the name is also searched with the year as part of the title
     * ("Space.1999.S01E01" is *Space: 1999*; see [MatchSearch.withYearInTitle]).
     */
    context(_: LoggingContext)
    private suspend fun lookUpShows(
        title: String,
        year: String?,
        bareYear: Boolean,
    ): Result<Pair<List<MediaSearchResult>, List<RankedMatch>>> {
        val found = search.searchFor<MediaSearchResult.TvShow>(title, year) { metadata.search(title, year) }
            .getOrElse { return Result.failure(it) }
        val titleYear = year?.takeIf { bareYear && titleOverride == null && yearOverride == null }
        val ranking = search.rank(found, title, year, reportMiss = titleYear == null)
        if (titleYear == null || (ranking.matches.firstOrNull()?.media as? CanonicalMedia.TvShow)?.year?.toString() == titleYear) {
            return Result.success(found to ranking.matches)
        }
        return search.withYearInTitle<MediaSearchResult.TvShow>(title, titleYear, found, ranking, noMatch = "No show from $titleYear matched")
    }
}
