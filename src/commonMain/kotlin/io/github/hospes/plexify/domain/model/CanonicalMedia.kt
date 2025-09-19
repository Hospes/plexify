package io.github.hospes.plexify.domain.model

// The "golden record" for a piece of media after verification.
sealed interface CanonicalMedia {

    data class TvShow(
        val title: String,
        val year: Int,
        val imdbId: String? = null,
        val tmdbId: String? = null,
        val tvdbId: String? = null,
    )

    data class Episode(
        val show: TvShow,
        val season: Int,
        val episode: Int,
        val title: String, // Episode-specific title
    ) : CanonicalMedia

    data class Movie(
        val title: String,
        val year: Int,
        val imdbId: String? = null,
        val tmdbId: String? = null,
        val tvdbId: String? = null,
    ) : CanonicalMedia
}