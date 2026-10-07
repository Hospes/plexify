package io.github.hospes.plexify.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response of `movie/{id}/external_ids` and `tv/{id}/external_ids`.
 * TMDB sends a missing IMDb ID as null or as an empty string; only the TV endpoint has `tvdb_id`.
 */
@Serializable
data class TmdbExternalIdsDto(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: Long? = null,
)
