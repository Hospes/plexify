package io.github.hospes.plexify.web.omdb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OmdbSearchResponse(
    @SerialName("titles") val items: List<OmdbMediaItem> = emptyList(),
)