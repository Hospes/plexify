package io.github.hospes.plexify.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbSearchResponseDto(
    @SerialName("results") val items: List<TmdbMediaItemDto> = emptyList(),
)

/** `search/movie` results, which carry no `media_type` to tell movies from shows. */
@Serializable
data class TmdbMovieSearchResponseDto(
    @SerialName("results") val items: List<TmdbMediaItemDto.Movie> = emptyList(),
)

/** `find/{external_id}` results: the movies and shows another database's ID names. */
@Serializable
data class TmdbFindResponseDto(
    @SerialName("movie_results") val movies: List<TmdbMediaItemDto.Movie> = emptyList(),
    @SerialName("tv_results") val shows: List<TmdbMediaItemDto.TvShow> = emptyList(),
)

/** `movie/{id}` with `append_to_response=alternative_titles`; the details carry the IMDb ID themselves. */
@Serializable
data class TmdbMovieDetailsDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("alternative_titles") val alternativeTitles: TmdbAlternativeTitlesDto = TmdbAlternativeTitlesDto(),
)

/** `tv/{id}` with `append_to_response=alternative_titles,external_ids`. */
@Serializable
data class TmdbTvDetailsDto(
    @SerialName("id") val id: String,
    @SerialName("name") val title: String,
    @SerialName("original_name") val originalTitle: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIdsDto = TmdbExternalIdsDto(),
    @SerialName("alternative_titles") val alternativeTitles: TmdbAlternativeTitlesDto = TmdbAlternativeTitlesDto(),
)