package io.github.hospes.plexify.web.omdb

import io.github.hospes.plexify.web.MediaServiceApi
import io.github.hospes.plexify.web.createHttpClientEngine
import io.github.hospes.plexify.web.nonstrict
import io.github.hospes.plexify.web.omdb.model.OmdbSearchResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*

// [ Base URL: http://www.omdbapi.com/?apikey=[yourkey]& ] -> https://www.omdbapi.com/
class OmdbApi(
    private val apiKey: String,
) : MediaServiceApi {

    private val httpClient by lazy {
        HttpClient(createHttpClientEngine()) {
            install(ContentNegotiation) { json(nonstrict) }

            defaultRequest {
                url {
                    takeFrom("http://www.omdbapi.com/")
                    parameters.append("apikey", apiKey)
                }
                headers.appendIfNameAbsent(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
        }
    }


    suspend fun search(title: String) = httpClient.get("") {
        parameter("s", title)
        parameter("page", 1)
    }.body<OmdbSearchResponse>()
}