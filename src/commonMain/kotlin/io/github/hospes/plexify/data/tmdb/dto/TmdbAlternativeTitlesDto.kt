package io.github.hospes.plexify.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response of `movie/{id}/alternative_titles` and `tv/{id}/alternative_titles`.
 * The movie endpoint returns the list under "titles", the TV endpoint under "results".
 */
@Serializable
data class TmdbAlternativeTitlesDto(
    @SerialName("titles") val titles: List<TmdbAlternativeTitleDto> = emptyList(),
    @SerialName("results") val results: List<TmdbAlternativeTitleDto> = emptyList(),
) {
    val all: List<TmdbAlternativeTitleDto> get() = titles + results
}

@Serializable
data class TmdbAlternativeTitleDto(
    @SerialName("iso_3166_1") val country: String? = null,
    @SerialName("title") val title: String,
    @SerialName("type") val type: String? = null,
)
