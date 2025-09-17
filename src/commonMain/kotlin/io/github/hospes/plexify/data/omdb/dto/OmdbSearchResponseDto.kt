package io.github.hospes.plexify.data.omdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OmdbSearchResponseDto(
    @SerialName("titles") val items: List<OmdbMediaItemDto> = emptyList(),
)