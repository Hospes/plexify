package io.github.hospes.plexify.web.imdb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImdbSearchResponse(
    @SerialName("titles") val titles: List<ImdbTitle>,
)