package io.github.hospes.plexify.data.imdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImdbSearchResponseDto(
    @SerialName("titles") val items: List<ImdbMediaItemDto> = emptyList(),
)