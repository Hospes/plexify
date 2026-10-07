package io.github.hospes.plexify.domain.model

// The "golden record" for a piece of media after verification.
sealed interface CanonicalMedia {

    data class TvShow(
        val title: String,
        val year: Int?, // null when the provider has no date yet (announced titles)
        val imdbId: String? = null,
        val tmdbId: String? = null,
        val tvdbId: String? = null,
    ) : CanonicalMedia

    data class Episode(
        val show: TvShow,
        val season: Int,
        val episode: Int,
        val title: String, // Episode-specific title
    ) : CanonicalMedia

    data class Movie(
        val title: String,
        val year: Int?, // null when the provider has no date yet (announced titles)
        val imdbId: String? = null,
        val tmdbId: String? = null,
        val tvdbId: String? = null,
    ) : CanonicalMedia

    data class Season(
        val show: TvShow,
        val seasonNumber: Int,
        val episodes: List<Episode>,
    )

    /**
     * An alternative ordering of a show's episodes (a TMDB "episode group"), e.g. an anime
     * TMDB keeps as one 24-episode season but releases split into two 12-episode cours.
     * Each part lists the show's real episodes in the order the alternative numbering uses.
     */
    data class EpisodeGroup(
        val name: String,
        val type: Type,
        val parts: List<Part>,
    ) {
        data class Part(
            val name: String,
            val order: Int,
            val episodes: List<Episode>,
        )

        enum class Type { ORIGINAL_AIR_DATE, ABSOLUTE, DVD, DIGITAL, STORY_ARC, PRODUCTION, TV }
    }
}