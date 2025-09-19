package io.github.hospes.plexify.domain.model

sealed interface MediaSearchResult {
    val title: String
    val year: String?
    val imdbId: String?
    val tmdbId: String?
    val tvdbId: String?
    val provider: String
    val matchConfidence: Int

    data class Movie(
        override val title: String,
        override val year: String?,
        override val imdbId: String? = null,
        override val tmdbId: String? = null,
        override val tvdbId: String? = null,
        override val provider: String,
        override val matchConfidence: Int = 0
    ) : MediaSearchResult

    data class TvShow(
        override val title: String,
        override val year: String?,
        override val imdbId: String? = null,
        override val tmdbId: String? = null,
        override val tvdbId: String? = null,
        override val provider: String,
        override val matchConfidence: Int = 0
    ) : MediaSearchResult
}