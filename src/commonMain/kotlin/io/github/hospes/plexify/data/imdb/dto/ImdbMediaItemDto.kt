package io.github.hospes.plexify.data.imdb.dto

import io.github.hospes.plexify.data.MediaItemDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImdbMediaItemDto(
    @SerialName("id") override val id: String,
    @SerialName("primaryTitle") override val title: String,
    @SerialName("originalTitle") val originalTitle: String,
    @SerialName("type") val type: String,
) : MediaItemDto