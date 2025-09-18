package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.data.createHttpClientEngine
import io.github.hospes.plexify.data.nonstrict
import io.github.hospes.plexify.data.tmdb.dto.TmdbMediaItemDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbSearchResponseDto
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*

class TmdbProvider(
    private val apiKey: String,
    private val accessToken: String? = null,
) : MetadataProvider {

    private val httpClient by lazy {
        HttpClient(createHttpClientEngine()) {
            install(ContentNegotiation) { json(nonstrict) }

            install(Auth) {
                bearer {
                    loadTokens { accessToken?.let { BearerTokens(accessToken = it, refreshToken = null) } }
                }
            }

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
            parameter("page", 1)
        }.body<TmdbSearchResponseDto>().items.mapNotNull { it.toDomainModel() }
    }
}

private fun TmdbMediaItemDto.toDomainModel(): MediaSearchResult? {
    return when (this) {
        is TmdbMediaItemDto.Movie -> MediaSearchResult(
            title = title,
            year = releaseDate.year.toString(),//releaseDate?.substringBefore("-"), // Extract year from "YYYY-MM-DD"
            imdbId = null, // TMDB search results do NOT include IMDb ID
            tmdbId = id, // The 'id' from this API IS the TMDB ID
            provider = "TMDb"
        )

        is TmdbMediaItemDto.TvShow -> null // Or map to a TvShow domain model later
    }
}