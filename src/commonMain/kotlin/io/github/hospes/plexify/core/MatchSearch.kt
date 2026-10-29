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
import io.github.hospes.plexify.domain.service.isNamedById
import io.github.hospes.plexify.logging.LoggingContext
import io.github.hospes.plexify.logging.debug
import io.github.hospes.plexify.logging.indent
import io.github.hospes.plexify.logging.status

/**
 * The searching and ranking [ShowMatcher] and [MovieMatcher] share: a search with the alias lookups that could change
 * its match, a ranking printed as it was decided, the record the ID in a path names ranked as a search result, and the
 * year closing a name read as part of the title.
 */
internal class MatchSearch(
    val metadata: MetadataRepository,
    val ranker: MatchRanker,
) {
    /**
     * The [T] results of [search] for [title] and [year], with their aliases looked up only when they could change the
     * best match the caller takes among those [accepts] keeps ([MatchRanker.aliasesCanChangeWinner]). The lookups are
     * those the provider always made, for the same results with every kind counted ([awaitingAliases]), with the same
     * failure rule ([MetadataRepository.withAlternativeTitles]). Fails when the search or the lookups failed.
     */
    context(_: LoggingContext)
    suspend inline fun <reified T : MediaSearchResult> searchFor(
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
     * qualifies, the reason is reported unless [reportMiss] is false (a ranking that a second search may redo; see
     * [reportMiss]).
     */
    context(_: LoggingContext)
    fun rank(
        results: List<MediaSearchResult>,
        parsedTitle: String,
        parsedYear: String?,
        reportMiss: Boolean = true,
    ): MatchRanker.Ranking = ranker.rank(results, parsedTitle, parsedYear).also { print(it, parsedTitle, reportMiss) }

    /**
     * Reports why [ranking], ranked for [parsedTitle] without reporting its miss, matched nothing, once no other search
     * follows. How each candidate scored is printed again in verbose mode, as the ranking that decided the file.
     */
    context(_: LoggingContext)
    fun reportMiss(ranking: MatchRanker.Ranking, parsedTitle: String) = print(ranking, parsedTitle, reportMiss = true)

    context(_: LoggingContext)
    private fun print(ranking: MatchRanker.Ranking, parsedTitle: String, reportMiss: Boolean) = indent {
        ranking.trace.forEach { debug(it) }
        ranking.miss?.let { reason ->
            if (reportMiss) status("No match for '$parsedTitle': $reason") else debug("No match yet: $reason")
        }
    }

    /**
     * [record], the [kind] the ID in the path names, ranked as a search result for the parsed title and year is (by
     * every title it is known by, the year, the confidence minimum), so a tag that isn't this file's (a show's tag on a
     * movie, a wrong one) gives way to the title search. A title named by its ID ("tmdb-1721501") has no title to rank:
     * the ID holds when the record's title is one plexify names by its ID and its year doesn't contradict the name's.
     * Null, with the reason reported, when the ID doesn't hold or names nothing (a merged or deleted record).
     */
    context(_: LoggingContext)
    fun rankById(id: MediaId, record: MediaSearchResult?, kind: String, parsedTitle: String, parsedYear: String?): RankedMatch? {
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
            return rank(listOf(ranked), parsedTitle, parsedYear, reportMiss = false).matches.firstOrNull() ?: run {
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
     * The year closing a name read as part of its title: "[title] [year]" searched as the whole title, after the search
     * for [title] found [found], ranked as [ranking] with no match from [year] ([noMatch] says so in verbose mode). Only
     * a [T] whose title has the year counts: the reading has no year to rule out the namesakes the first search rejected
     * ("Dracula" for "Dracula 1974"). Its match wins if it scores higher; else [ranking] stands, and its miss, if any, is
     * reported. Gives every result found, with the ranked candidates. Fails when the search failed and [ranking] has no
     * candidate (see [unlessFailedAfter]).
     */
    context(_: LoggingContext)
    suspend inline fun <reified T : MediaSearchResult> withYearInTitle(
        title: String,
        year: String,
        found: List<MediaSearchResult>,
        ranking: MatchRanker.Ranking,
        noMatch: String,
    ): Result<Pair<List<MediaSearchResult>, List<RankedMatch>>> {
        val joinedTitle = "$title $year"
        val namesYear = { media: CanonicalMedia ->
            when (media) {
                is CanonicalMedia.Movie -> year in media.title
                is CanonicalMedia.TvShow -> year in media.title
                else -> false
            }
        }
        debug("$noMatch; trying '$joinedTitle' as the whole title")
        val joined = searchFor<T>(joinedTitle, null, namesYear) { metadata.search(joinedTitle, null) }
            .unlessFailedAfter(ranking.matches)
            .getOrElse { return Result.failure(it) }
        val joinedRanking = rank(joined, joinedTitle, null, reportMiss = false).matches.filter { namesYear(it.media) }
        val joinedScore = joinedRanking.firstOrNull()?.score
        if (joinedScore != null && joinedScore > (ranking.matches.firstOrNull()?.score ?: 0.0)) {
            debug("'$joinedTitle' matches better (${joinedScore.format(2)})")
            return Result.success(found + joined to joinedRanking)
        }
        if (ranking.matches.isEmpty()) reportMiss(ranking, title)
        return Result.success(found + joined to ranking.matches)
    }
}

/**
 * This result of a search that followed one finding [found]: a failure is left out (as no results) when [found] has a
 * candidate; with none yet, the failed search decides the file.
 */
context(_: LoggingContext)
internal fun <T> Result<List<T>>.unlessFailedAfter(found: List<RankedMatch>): Result<List<T>> = fold(
    onSuccess = { Result.success(it) },
    onFailure = { error ->
        if (found.isEmpty()) return@fold Result.failure(error)
        debug("Search failed: ${error.message}")
        Result.success(emptyList())
    },
)
