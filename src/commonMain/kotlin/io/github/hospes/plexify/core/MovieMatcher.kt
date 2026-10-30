package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.matching.RankedMatch
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.logging.LoggingContext
import io.github.hospes.plexify.logging.debug
import io.github.hospes.plexify.logging.indent
import io.github.hospes.plexify.logging.status

/** The movie a movie file is, with its external IDs: the movie the ID in its path names, else the best its title matches. */
internal class MovieMatcher(
    private val metadata: MetadataRepository,
    private val search: MatchSearch,
    private val titleOverride: String?,
    private val yearOverride: String?,
) {
    /** The movie a file is, or why it has none. */
    sealed interface Match {
        data class Found(val movie: CanonicalMedia.Movie) : Match

        /** The searches found no movie at all. */
        data object NoResults : Match

        /** No movie found matches confidently. */
        data object NoConfidentMatch : Match
    }

    /** The [Match] for [parsed]. Fails when a lookup failed: that says nothing about the movie, so the file is a failure. */
    context(_: LoggingContext)
    suspend fun match(parsed: ParsedMediaInfo.Movie): Result<Match> {
        findMovieById(parsed).getOrElse { return Result.failure(it) }?.let { return Result.success(Match.Found(it)) }

        val (results, ranking) = lookUpMovie(parsed.title, parsed.year).getOrElse { return Result.failure(it) }
        if (results.isEmpty()) return Result.success(Match.NoResults)
        val best = ranking.firstOrNull()?.media as? CanonicalMedia.Movie ?: return Result.success(Match.NoConfidentMatch)
        return metadata.withImdbId(best).map { Match.Found(it) }
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

    /**
     * Every movie result found for [title] and [year], with the ranked candidates. Fails when a search
     * failed and nothing else found a candidate.
     *
     * The general search returns one page and ignores the year, so when its best match is not from
     * the filename year, two more searches follow. The same title searched with the year finds a
     * remade film missing from that page. Then, unless the user set the title or the year, the year
     * is read as part of the title ("Wonder.Woman.1984.1080p"; see [MatchSearch.withYearInTitle]).
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

        debug("No match from $year among the first results; searching again with the year")
        val byYear = search.searchFor<MediaSearchResult.Movie>(title, year) { metadata.searchMovies(title, year) }
            .unlessFailedAfter(firstRanking)
            .getOrElse { return Result.failure(it) }
        val merged = first.mergedWith(byYear)
        val ranking = search.rank(merged, title, year, reportMiss = false)

        if (ranking.matches.bestYear() != year && titleOverride == null && yearOverride == null) {
            return search.withYearInTitle<MediaSearchResult.Movie>(title, year, merged, ranking, noMatch = "Still no match from $year")
        }
        if (ranking.matches.isEmpty()) search.reportMiss(ranking, title)
        return Result.success(merged to ranking.matches)
    }

    /**
     * These results and [later] ones, each record once, in the order first found (it breaks ties). A record both
     * searches found keeps the copy with the most aliases, then the higher confidence, else the first: each search looks up the
     * aliases of a few results only, and the other copy may lack the alias that names the film ("Kimi no Na wa.").
     */
    private fun List<MediaSearchResult>.mergedWith(later: List<MediaSearchResult>): List<MediaSearchResult> =
        (this + later).groupBy { listOf(it.provider, it.tmdbId ?: "${it.title}:${it.year}") }.values
            .map { copies -> copies.maxWith(compareBy({ it.alternativeTitles.size }, { it.matchConfidence })) }

    private fun List<RankedMatch>.bestYear(): String? = (firstOrNull()?.media as? CanonicalMedia.Movie)?.year?.toString()
}
