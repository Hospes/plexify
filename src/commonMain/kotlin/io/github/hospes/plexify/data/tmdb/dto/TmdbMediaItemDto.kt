package io.github.hospes.plexify.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("media_type")
sealed interface TmdbMediaItemDto {
    @Serializable
    @SerialName("movie")
    data class Movie(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("original_title") val originalTitle: String,
        @SerialName("release_date") val releaseDate: String? = null,
    ) : TmdbMediaItemDto

    @Serializable
    @SerialName("tv")
    data class TvShow(
        @SerialName("id") val id: String,
        @SerialName("name") val title: String,
        @SerialName("original_name") val originalTitle: String,
        @SerialName("first_air_date") val firstAirDate: String? = null,
    ) : TmdbMediaItemDto


    @Serializable
    data object Unknown : TmdbMediaItemDto
}