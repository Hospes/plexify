package io.github.hospes.plexify.data.tvdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TvdbAuthBodyRequestDto(
    @SerialName("apikey") val apiKey: String,
)