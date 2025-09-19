package io.github.hospes.plexify.data.tvdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TvdbAuthBodyResponseDto(
    @SerialName("data") val data: Data,
    @SerialName("status") val status: String,
) {
    @Serializable
    data class Data(
        @SerialName("token") val token: String,
    )
}