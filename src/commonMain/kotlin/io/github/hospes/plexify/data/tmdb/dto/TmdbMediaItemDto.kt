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
        @SerialName("release_date") val releaseDate: String? = null,
    ) : TmdbMediaItemDto

    @Serializable
    @SerialName("tv")
    data class TvShow(
        @SerialName("id") override val id: String,
        @SerialName("name") override val title: String,
        @SerialName("original_name") val originalTitle: String,
        @SerialName("first_air_date") val firstAirDate: String? = null,
    ) : TmdbMediaItemDto


    @Serializable
    data object Unknown : TmdbMediaItemDto {
        override val id: String get() = "unknown"
        override val title: String get() = ""
    }
}


@Serializable
data class TmdbEpisodeDto(
    @SerialName("id") val id: String,
    @SerialName("name") val title: String,
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_number") val episodeNumber: Int,
)