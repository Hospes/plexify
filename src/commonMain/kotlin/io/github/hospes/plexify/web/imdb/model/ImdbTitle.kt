package io.github.hospes.plexify.web.imdb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImdbTitle(
    @SerialName("id") val id: String,
    @SerialName("primaryTitle") val primaryTitle: String,
    @SerialName("originalTitle") val originalTitle: String,
)