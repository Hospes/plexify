package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.data.calculateTitleConfidence
import io.github.hospes.plexify.data.createHttpClientEngine
import io.github.hospes.plexify.data.nonstrict
import io.github.hospes.plexify.data.tmdb.dto.TmdbEpisodeDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbMediaItemDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbSearchResponseDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbSeasonDto
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*

class TmdbProvider(
    private val apiKey: String,
    private val accessToken: String? = null,
) : MetadataProvider {
    override val id: String = "tmdb"
    override val supportedIds: Set<String> = setOf("tmdbid")

    private val httpClient by lazy {
        HttpClient(createHttpClientEngine()) {
            install(ContentNegotiation) { json(nonstrict) }

            install(Auth) {
                bearer {
                    loadTokens { accessToken?.let { BearerTokens(accessToken = it, refreshToken = null) } }
                }
            }

//            install(Logging) {
//                logger = Logger.DEFAULT
//                level = LogLevel.ALL
//            }

            defaultRequest {
                url {
                    takeFrom("https://api.themoviedb.org/3/")
                    if (accessToken == null) parameters.append("api_key", apiKey)
                }
                headers.appendIfNameAbsent(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
        }
    }


    override suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>> = Result.runCatching {
        httpClient.get("search/multi") {
            parameter("query", title)
            parameter("include_adult", true)    // We need to include all possible movies/shows even if it's R+ rating
            parameter("page", 1)
        }.body<TmdbSearchResponseDto>().items.mapNotNull { it.toDomainModel(title) }
    }

    override suspend fun episode(
        show: CanonicalMedia.TvShow,
        season: Int,
        episode: Int
    ): Result<CanonicalMedia.Episode> = Result.runCatching {
        requireNotNull(show.tmdbId) { "TMDb ID is required to fetch episode details." }
        val dto = httpClient.get("tv/${show.tmdbId}/season/$season/episode/$episode")
            .body<TmdbEpisodeDto>()

        CanonicalMedia.Episode(
            show = show,
            season = dto.seasonNumber,
            episode = dto.episodeNumber,
            title = dto.title,
        )
    }

    override suspend fun season(
        show: CanonicalMedia.TvShow,
        season: Int,
    ): Result<CanonicalMedia.Season> = Result.runCatching {
        requireNotNull(show.tmdbId) { "TMDb ID is required to fetch season details." }
        val dto = httpClient.get("tv/${show.tmdbId}/season/$season")
            .body<TmdbSeasonDto>()

        CanonicalMedia.Season(
            show = show,
            seasonNumber = dto.seasonNumber,
            episodes = dto.episodes.map { ep ->
                CanonicalMedia.Episode(
                    show = show,
                    season = ep.seasonNumber,
                    episode = ep.episodeNumber,
                    title = ep.name,
                )
            },
        )
    }
}

private fun TmdbMediaItemDto.toDomainModel(queryTitle: String): MediaSearchResult? {
    val confidence = calculateTitleConfidence(queryTitle, this.title)
    return when (this) {
        is TmdbMediaItemDto.Movie -> MediaSearchResult.Movie(
            title = title,
            //year = releaseDate?.year?.toString(),
            year = releaseDate?.substringBefore("-")?.ifBlank { null }, // Extract year from "YYYY-MM-DD"
            tmdbId = id,
            provider = "TMDb",
            matchConfidence = confidence,
        )

        is TmdbMediaItemDto.TvShow -> MediaSearchResult.TvShow(
            title = title,
            //year = firstAirDate?.year?.toString(),//releaseDate?.substringBefore("-"), // Extract year from "YYYY-MM-DD"
            year = firstAirDate?.substringBefore("-")?.ifBlank { null }, // Extract year from "YYYY-MM-DD"
            tmdbId = id,
            provider = "TMDb",
            matchConfidence = confidence,
        )

        else -> null
    }
}