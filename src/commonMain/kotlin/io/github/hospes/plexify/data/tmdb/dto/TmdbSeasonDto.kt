package io.github.hospes.plexify.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbSeasonDto(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String,
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episodes") val episodes: List<TmdbSeasonEpisodeDto> = emptyList(),
)

@Serializable
data class TmdbSeasonEpisodeDto(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String,
    @SerialName("episode_number") val episodeNumber: Int,
    @SerialName("season_number") val seasonNumber: Int,
)
