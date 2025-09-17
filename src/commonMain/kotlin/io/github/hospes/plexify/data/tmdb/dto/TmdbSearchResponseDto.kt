package io.github.hospes.plexify.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbSearchResponseDto(
    @SerialName("results") val items: List<TmdbMediaItemDto> = emptyList(),
)