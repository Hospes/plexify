package io.github.hospes.plexify.web.omdb.model

import io.github.hospes.plexify.web.MediaItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OmdbMediaItem(
    @SerialName("id") override val id: String,
    @SerialName("primaryTitle") override val title: String,
    @SerialName("originalTitle") val originalTitle: String,
    @SerialName("type") val type: String,
) : MediaItem