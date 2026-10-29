package io.github.hospes.plexify.domain.model

/**
 * The aliases a provider lists for one of its records (translations, romanizations, working titles), as its lookup
 * answers them: the same for every search that finds the record, so a run looks them up once.
 */
data class AlternativeTitles(
    val titles: List<String>,
    /** Titles naming one season of a show (an anime arc's own title), by season number; the record's own titles too. */
    val seasonTitles: Map<String, Int> = emptyMap(),
) {
    companion object {
        /** A record with no aliases, or one the provider doesn't have. */
        val NONE = AlternativeTitles(emptyList())
    }
}
