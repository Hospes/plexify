package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.tmdb.dto.TmdbEpisodeGroupDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbExternalIdsDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbMediaItemDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbMovieDetailsDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbSeasonDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbTvDetailsDto
import io.github.hospes.plexify.domain.matching.calculateTitleConfidence
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import io.github.hospes.plexify.domain.model.MediaSearchResult

internal fun TmdbMediaItemDto.Movie.toDomainModel(queryTitle: String) = MediaSearchResult.Movie(
    title = title,
    year = releaseDate?.substringBefore("-")?.ifBlank { null }, // Extract year from "YYYY-MM-DD"
    tmdbId = id,
    provider = "TMDb",
    matchConfidence = maxOf(
        calculateTitleConfidence(queryTitle, title),
        calculateTitleConfidence(queryTitle, originalTitle),
    ),
    originalTitle = originalTitle,
)

internal fun TmdbMediaItemDto.toDomainModel(queryTitle: String): MediaSearchResult? {
    return when (this) {
        is TmdbMediaItemDto.Movie -> toDomainModel(queryTitle)

        is TmdbMediaItemDto.TvShow -> MediaSearchResult.TvShow(
            title = title,
            year = firstAirDate?.substringBefore("-")?.ifBlank { null }, // Extract year from "YYYY-MM-DD"
            tmdbId = id,
            provider = "TMDb",
            matchConfidence = maxOf(
                calculateTitleConfidence(queryTitle, title),
                calculateTitleConfidence(queryTitle, originalTitle),
            ),
            originalTitle = originalTitle,
        )

        else -> null
    }
}

internal fun TmdbMovieDetailsDto.toDomainModel() = MediaSearchResult.Movie(
    title = title,
    year = releaseDate?.substringBefore("-")?.ifBlank { null },
    imdbId = imdbId?.ifBlank { null },
    tmdbId = id,
    provider = "TMDb",
    originalTitle = originalTitle,
    alternativeTitles = alternativeTitles.all.map { it.title },
)

internal fun TmdbTvDetailsDto.toDomainModel() = MediaSearchResult.TvShow(
    title = title,
    year = firstAirDate?.substringBefore("-")?.ifBlank { null },
    imdbId = externalIds.imdbId?.ifBlank { null },
    tmdbId = id,
    provider = "TMDb",
    originalTitle = originalTitle,
    alternativeTitles = alternativeTitles.all.map { it.title },
    // The show's own titles name it as a whole, whatever season a tag gives them.
    seasonTitles = alternativeTitles.seasonTitles() - setOfNotNull(title, originalTitle),
)

internal fun TmdbSeasonDto.toDomainModel(show: CanonicalMedia.TvShow) = CanonicalMedia.Season(
    show = show,
    seasonNumber = seasonNumber,
    episodes = episodes.map { ep ->
        CanonicalMedia.Episode(
            show = show,
            season = ep.seasonNumber,
            episode = ep.episodeNumber,
            title = ep.name,
        )
    },
)

// TMDB numbers the types 1..7 in this order.
internal fun Int.toEpisodeGroupType(): CanonicalMedia.EpisodeGroup.Type? =
    CanonicalMedia.EpisodeGroup.Type.entries.getOrNull(this - 1)

internal fun TmdbEpisodeGroupDto.toDomainModel(
    show: CanonicalMedia.TvShow,
    type: CanonicalMedia.EpisodeGroup.Type,
) = CanonicalMedia.EpisodeGroup(
    name = name,
    type = type,
    parts = groups.map { part ->
        CanonicalMedia.EpisodeGroup.Part(
            name = part.name,
            order = part.order,
            episodes = part.episodes.sortedBy { it.order }.map { ep ->
                CanonicalMedia.Episode(
                    show = show,
                    season = ep.seasonNumber,
                    episode = ep.episodeNumber,
                    title = ep.name,
                )
            },
        )
    },
)

internal fun TmdbExternalIdsDto.toDomainModel() = ExternalIds(imdbId = imdbId?.ifBlank { null })
