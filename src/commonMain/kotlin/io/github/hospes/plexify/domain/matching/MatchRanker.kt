package io.github.hospes.plexify.domain.matching

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** A candidate from [MatchRanker.rank]: its record consolidated across providers, and how it matched. */
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

/**
 * Ranks search results against the title and year parsed from a filename (see [MatchScoring] for the score). Pure: it
 * prints nothing, and returns what the caller reports in the [Ranking].
 *
 * [yearOverride], the year the user set, is a hard filter, not a scoring signal: a candidate whose known year differs
 * is discarded. A candidate without a year is kept, since there is nothing to verify against.
 */
internal class MatchRanker(private val yearOverride: String? = null) {

    /**
     * [matches]: every candidate scoring at least [MatchScoring.MINIMUM_CONFIDENCE_SCORE], best first, each
     * consolidated across providers. [miss]: why none qualifies, when there were results to rank; null otherwise.
     * [trace]: how each candidate scored, in order, for the verbose output.
     */
    class Ranking(val matches: List<RankedMatch>, val miss: String?, val trace: List<String>)

    fun rank(results: List<MediaSearchResult>, parsedTitle: String, parsedYear: String?): Ranking {
        if (results.isEmpty()) return Ranking(emptyList(), miss = null, trace = emptyList())

        val trace = mutableListOf<String>()
        trace += "Consolidating ${results.size} results for title: '$parsedTitle' year: '$parsedYear'"

        val groupedByMedia = results.groupBy { it.candidateKey }
        trace += "${groupedByMedia.size} unique media candidates found."

        // Collected so a failed lookup can report *why* in the concise output.
        val yearRejected = mutableListOf<String>()
        val yearMismatched = mutableListOf<String>()

        val scoredGroups = groupedByMedia.values.mapNotNull { group ->
            val representative = group.first()

            val overrideYear = yearOverride?.toIntOrNull()
            val candidateYear = representative.year?.toIntOrNull()
            if (overrideYear != null && candidateYear != null && candidateYear != overrideYear) {
                trace += "Candidate: '${representative.title} (${representative.year})' | Discarded (year does not match override $overrideYear)"
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
                trace += "Candidate: '${representative.title} (${representative.year})' | Discarded (title similarity too low)"
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
                trace += "Candidate: '${representative.title} (${representative.year})' | Discarded (year does not match $parsedY)"
                yearMismatched += "'${representative.title} (${representative.year})'"
                return@mapNotNull null
            }
            score += yearScore ?: 0.0

            score += (group.distinctBy { it.provider }.size - 1) * MatchScoring.PROVIDER_AGREEMENT_BONUS

            // Bonus based on the average confidence score reported by the providers
            val avgProviderConfidence = group.map { it.matchConfidence }.average()
            score += avgProviderConfidence / MatchScoring.PROVIDER_CONFIDENCE_DIVISOR

            trace += "Candidate: '${representative.title} (${representative.year})' | Score: ${score.format(2)}$matchedVia"
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
            return Ranking(emptyList(), miss = reason, trace = trace)
        }

        trace += "Best match selected: '${bestGroup.group.first().title}' with score ${bestGroup.score.format(2)}"

        // sortedByDescending is stable, so the best stays the one maxByOrNull picks on a tie.
        val matches = scoredGroups
            .filter { it.score >= MatchScoring.MINIMUM_CONFIDENCE_SCORE }
            .sortedByDescending { it.score }
            .map { RankedMatch(consolidate(it.group, parsedYear), it.similarity, it.yearScore, it.titleSeason, it.namesUnplacedArc, it.score) }
        return Ranking(matches, miss = null, trace = trace)
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
}

/** Merges one candidate's results across providers into a golden record with all their IDs. */
internal fun consolidate(groupItems: List<MediaSearchResult>, parsedYear: String?): CanonicalMedia {
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
 * A negative score is a contradiction, not a weak signal: [MatchRanker.rank] discards the candidate.
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

// The first few rejected candidates for a "No match" line, with a count of the rest.
private fun List<String>.listed(): String =
    take(3).joinToString(", ") + if (size > 3) " and ${size - 3} more" else ""

// Helper function to format a Double to a specific number of decimal places in a multiplatform-safe way.
internal fun Double.format(digits: Int): String {
    val factor = 10.0.pow(digits)
    if (this.isNaN() || this.isInfinite()) {
        return this.toString()
    }
    val scaled = (this * factor).roundToInt()
    val intPart = scaled / factor.toInt()
    val fracPart = scaled.mod(factor.toInt())
    return "$intPart.${fracPart.toString().padStart(digits, '0')}"
}
