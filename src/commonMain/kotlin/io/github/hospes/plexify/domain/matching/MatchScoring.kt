package io.github.hospes.plexify.domain.matching

/**
 * The weights and thresholds that decide which provider record a file matches, in one place.
 * Changing a value changes which files match: measure it against the corpus test
 * (`core/corpus/CorpusTest`, which pins every target path) and `MediaConsolidationTest`.
 *
 * `MediaProcessor.rankMatches` groups the search results by candidate (TMDB ID, else IMDb ID) and
 * scores each group:
 *
 * ```
 * score = TITLE_WEIGHT · similarity                        similarity < MIN_TITLE_SIMILARITY → discarded
 *       + year score                                       no year on either side → 0; negative → discarded
 *       + PROVIDER_AGREEMENT_BONUS · (providers − 1)       distinct providers in the group
 *       + mean(provider confidence) / PROVIDER_CONFIDENCE_DIVISOR
 * ```
 *
 * The groups scoring at least [MINIMUM_CONFIDENCE_SCORE] are the matches, best first.
 *
 * - **similarity**: [titleSimilarity] (normalized Levenshtein, 0..1) between the parsed title and the
 *   closest title of the group (title, original title, aliases), both reduced to lowercase letters
 *   and digits.
 * - **year score** (`yearScore`): [EXACT_YEAR_SCORE] for the same year, [ADJACENT_YEAR_SCORE] one
 *   year off either way. A show that premiered two or more years before the filename year (an
 *   episode's year is its season's) gets [ADJACENT_YEAR_SCORE] less [SHOW_YEAR_DECAY] per year
 *   beyond the first, but at least [SHOW_YEAR_FLOOR]. Anything else is [YEAR_MISMATCH_SCORE] and
 *   the candidate is discarded, so only the sign of that value matters.
 * - **providers**: always 1 today, since `App` gives `MetadataService` only TMDB, so the agreement
 *   term is 0.
 * - **provider confidence**: `MediaSearchResult.matchConfidence` (0..100), the best
 *   [calculateTitleConfidence] between the query and the result's title or original title, and its
 *   aliases once they are looked up (see [ALT_TITLES_CONFIDENCE_THRESHOLD]). It adds 0..5.
 *
 * Two consequences of this arithmetic, documented here and deliberately left as they are:
 * - **The title is counted twice, with two normalizations.** [titleSimilarity] compares letters
 *   and digits only, while [calculateTitleConfidence] only lowercases. "Spider-Man" against
 *   "Spiderman" has similarity 1.0 (10 points) but confidence 90 (4.5 points instead of 5), so
 *   punctuation can break a tie between candidates.
 * - **[MINIMUM_CONFIDENCE_SCORE] rarely decides.** A candidate past the similarity cut already has
 *   4.0 from its title, and when the filename and the candidate both have a year, at least
 *   [SHOW_YEAR_FLOOR] more: 5.0; a second provider would add [PROVIDER_AGREEMENT_BONUS]. It can
 *   fall below the minimum only when a year is missing, one provider returned it, its similarity is
 *   under 0.5, and its confidence is under `100 − 200 · similarity` (under 20 at 0.40, under 10 at
 *   0.45). Both title terms measure nearly the same thing, so in practice [MIN_TITLE_SIMILARITY]
 *   is the gate.
 */
object MatchScoring {
    /** Below this title similarity a candidate is discarded, whatever else it has. */
    const val MIN_TITLE_SIMILARITY = 0.4

    /** Points per unit of title similarity (0..1). */
    const val TITLE_WEIGHT = 10.0

    /** The filename year is the candidate's. */
    const val EXACT_YEAR_SCORE = 10.0

    /** The years differ by one (release dates often disagree by a year). */
    const val ADJACENT_YEAR_SCORE = 5.0

    /** Per year a show premiered before the filename year, beyond the first, off [ADJACENT_YEAR_SCORE]. */
    const val SHOW_YEAR_DECAY = 0.1

    /** The least a show that premiered before the filename year gets, however long before. */
    const val SHOW_YEAR_FLOOR = 1.0

    /** The years contradict each other: a remake or a namesake. Negative, so the candidate is discarded. */
    const val YEAR_MISMATCH_SCORE = -10.0

    /** Per provider beyond the first that returned the candidate. */
    const val PROVIDER_AGREEMENT_BONUS = 2.0

    /** Divides the mean provider confidence (0..100), so it is a bonus, not the main driver of the score. */
    const val PROVIDER_CONFIDENCE_DIVISOR = 20.0

    /** A candidate scoring below this is a poor match. */
    const val MINIMUM_CONFIDENCE_SCORE = 5.0

    /** Runner-up shows tried when the best match lacks the file's season. */
    const val MAX_FALLBACK_SHOWS = 3

    /**
     * A search result whose title or original title already has this provider confidence doesn't
     * get its alternative titles looked up. TMDB matches aliases (e.g. romaji anime titles) server-side
     * but returns only those two titles, so for the other results the alias may be what matched.
     */
    const val ALT_TITLES_CONFIDENCE_THRESHOLD = 60.0

    /**
     * Alternative-titles lookups per search, at most: only the first few unconvincing results get
     * one. TMDB orders results by relevance, so the alias match (if any) is expected near the top.
     */
    const val MAX_ALT_TITLES_LOOKUPS = 3
}
