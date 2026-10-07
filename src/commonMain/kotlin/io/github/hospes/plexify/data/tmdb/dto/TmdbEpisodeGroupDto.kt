package io.github.hospes.plexify.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response of `tv/{id}/episode_groups`: the alternative orderings defined for a show. */
@Serializable
data class TmdbEpisodeGroupsDto(
    @SerialName("results") val results: List<TmdbEpisodeGroupSummaryDto> = emptyList(),
)

@Serializable
data class TmdbEpisodeGroupSummaryDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("type") val type: Int,
    @SerialName("group_count") val groupCount: Int = 0,
    @SerialName("episode_count") val episodeCount: Int = 0,
)

/** Response of `tv/episode_group/{id}`: the ordering split into its parts. */
@Serializable
data class TmdbEpisodeGroupDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("type") val type: Int,
    @SerialName("groups") val groups: List<TmdbEpisodeGroupPartDto> = emptyList(),
)

@Serializable
data class TmdbEpisodeGroupPartDto(
    @SerialName("name") val name: String,
    @SerialName("order") val order: Int,
    @SerialName("episodes") val episodes: List<TmdbEpisodeGroupEpisodeDto> = emptyList(),
)

@Serializable
data class TmdbEpisodeGroupEpisodeDto(
    @SerialName("name") val name: String,
    @SerialName("order") val order: Int,
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_number") val episodeNumber: Int,
)
