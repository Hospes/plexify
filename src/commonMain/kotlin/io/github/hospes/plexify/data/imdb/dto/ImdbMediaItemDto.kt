package io.github.hospes.plexify.data.imdb.dto

import io.github.hospes.plexify.data.MediaItemDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImdbMediaItemDto : MediaItemDto {
    @Serializable
    @SerialName("movie")
    data class Movie(
        @SerialName("id") override val id: String,
        @SerialName("primaryTitle") override val title: String,
        @SerialName("originalTitle") val originalTitle: String,
        @SerialName("startYear") val startYear: Int,    // Imdb represent year as single int (2019)
    ) : ImdbMediaItemDto

    @Serializable
    @SerialName("tvSeries")
    data class TvShow(
        @SerialName("id") override val id: String,
        @SerialName("primaryTitle") override val title: String,
        @SerialName("originalTitle") val originalTitle: String,
        @SerialName("startYear") val startYear: Int,    // Imdb represent year as single int (2019)
    ) : ImdbMediaItemDto


    @Serializable
    data object Unknown : ImdbMediaItemDto {
        override val id: String get() = "unknown"
        override val title: String get() = ""
    }
}


@Serializable
data class ImdbEpisodeDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("season") val seasonNumber: String,
    @SerialName("episodeNumber") val episodeNumber: Int,
)