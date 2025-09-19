package io.github.hospes.plexify.data.tvdb

import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.data.createHttpClientEngine
import io.github.hospes.plexify.data.nonstrict
import io.github.hospes.plexify.data.tvdb.dto.TvdbAuthBodyRequestDto
import io.github.hospes.plexify.data.tvdb.dto.TvdbAuthBodyResponseDto
import io.github.hospes.plexify.domain.model.CanonicalMedia
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

class TvDbProvider(
    private val apiKey: String,
) : MetadataProvider {

    // This http client required for single purpose to get AuthToken for the next api calls to TheTVDB
    private val authHttpClient by lazy {
        HttpClient(createHttpClientEngine()) {
            install(ContentNegotiation) { json(nonstrict) }
            defaultRequest {
                url("https://api4.thetvdb.com/v4/")
                headers.appendIfNameAbsent(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
        }
    }

    private suspend fun login(apiKey: String): Result<String> = Result.runCatching {
        authHttpClient.post("login") {
            setBody(TvdbAuthBodyRequestDto(apiKey))
        }.body<TvdbAuthBodyResponseDto>().data.token
    }


    private val httpClient by lazy {
        HttpClient(createHttpClientEngine()) {
            install(ContentNegotiation) { json(nonstrict) }

            install(Auth) {
                bearer {
                    loadTokens {
                        val accessToken = login(apiKey).getOrNull() // We have to cache access token for 30 days
                        accessToken?.let { BearerTokens(accessToken = it, refreshToken = null) }
                    }

                    refreshTokens {
                        val accessToken = login(apiKey).getOrNull() // We have to cache access token for 30 days
                        accessToken?.let { BearerTokens(accessToken = it, refreshToken = null) }
                    }
                }
            }

            defaultRequest {
                url("https://api4.thetvdb.com/v4/")
                headers.appendIfNameAbsent(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
        }
    }


    override suspend fun search(
        title: String,
        year: String?,
    ): Result<List<MediaSearchResult>> {
        return Result.failure(UnsupportedOperationException("TheTVDB provider is not implemented yet."))
    }

    override suspend fun episode(
        show: CanonicalMedia.TvShow,
        season: Int,
        episode: Int
    ): Result<CanonicalMedia.Episode> {
        return Result.failure(UnsupportedOperationException("TheTVDB provider is not implemented yet."))
    }
}