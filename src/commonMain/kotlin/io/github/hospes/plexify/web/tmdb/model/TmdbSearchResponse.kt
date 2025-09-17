package io.github.hospes.plexify.web.tmdb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbSearchResponse(
    @SerialName("results") val items: List<TmdbMediaItem> = emptyList(),
)