package io.github.hospes.plexify.web.tmdb.model

import io.github.hospes.plexify.web.MediaItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("media_type")
sealed interface TmdbMediaItem : MediaItem {
    @Serializable
    @SerialName("movie")
    data class Movie(
        @SerialName("id") override val id: String,
        @SerialName("title") override val title: String,
        @SerialName("original_title") val originalTitle: String,
    ) : TmdbMediaItem

    @Serializable
    @SerialName("tv")
    data class TvShow(
        @SerialName("id") override val id: String,
        @SerialName("name") override val title: String,
        @SerialName("original_name") val originalTitle: String,
    ) : TmdbMediaItem
}