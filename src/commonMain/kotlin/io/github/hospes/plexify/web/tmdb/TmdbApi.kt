package io.github.hospes.plexify.web.tmdb

import io.github.hospes.plexify.web.MediaServiceApi
import io.github.hospes.plexify.web.createHttpClientEngine
import io.github.hospes.plexify.web.nonstrict
import io.github.hospes.plexify.web.tmdb.model.TmdbSearchResponse
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

class TmdbApi(
    private val apiKey: String,
    private val accessToken: String? = null,
) : MediaServiceApi {

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


    suspend fun search(title: String) = httpClient.get("search/multi") {
        parameter("query", title)
        parameter("page", 1)
    }.body<TmdbSearchResponse>()
}