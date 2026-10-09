package io.github.hospes.plexify.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbSearchResponseDto(
    @SerialName("results") val items: List<TmdbMediaItemDto> = emptyList(),
)

/** `search/movie` results, which carry no `media_type` to tell movies from shows. */
@Serializable
data class TmdbMovieSearchResponseDto(
    @SerialName("results") val items: List<TmdbMediaItemDto.Movie> = emptyList(),
)