package io.github.hospes.plexify.domain.model

sealed interface MediaSearchResult {
    val title: String
    val year: String?
    val imdbId: String?
    val tmdbId: String?
    val tvdbId: String?
    val provider: String
    val matchConfidence: Double

    /** Title in the media's original language, when the provider supplies one. */
    val originalTitle: String?

    /** Known aliases (translations, romanizations, working titles) usable for matching. */
    val alternativeTitles: List<String>

    /** Every title this result is known by, for similarity scoring. */
    val allTitles: List<String>
        get() = (listOf(title) + listOfNotNull(originalTitle) + alternativeTitles).distinct()

    data class Movie(
        override val title: String,
        override val year: String?,
        override val imdbId: String? = null,
        override val tmdbId: String? = null,
        override val tvdbId: String? = null,
        override val provider: String,
        override val matchConfidence: Double = 0.0,
        override val originalTitle: String? = null,
        override val alternativeTitles: List<String> = emptyList(),
    ) : MediaSearchResult

    data class TvShow(
        override val title: String,
        override val year: String?,
        override val imdbId: String? = null,
        override val tmdbId: String? = null,
        override val tvdbId: String? = null,
        override val provider: String,
        override val matchConfidence: Double = 0.0,
        override val originalTitle: String? = null,
        override val alternativeTitles: List<String> = emptyList(),
    ) : MediaSearchResult
}