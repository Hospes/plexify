package io.github.hospes.plexify.data.tmdb.dto

import io.github.hospes.plexify.data.MediaItemDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("media_type")
sealed interface TmdbMediaItemDto : MediaItemDto {
    @Serializable
    @SerialName("movie")
    data class Movie(
        @SerialName("id") override val id: String,
        @SerialName("title") override val title: String,
        @SerialName("original_title") val originalTitle: String,
    ) : TmdbMediaItemDto

    @Serializable
    @SerialName("tv")
    data class TvShow(
        @SerialName("id") override val id: String,
        @SerialName("name") override val title: String,
        @SerialName("original_name") val originalTitle: String,
    ) : TmdbMediaItemDto
}