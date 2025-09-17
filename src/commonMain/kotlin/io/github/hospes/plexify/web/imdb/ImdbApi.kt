package io.github.hospes.plexify.web.imdb

import io.github.hospes.plexify.web.MediaServiceApi
import io.github.hospes.plexify.web.createHttpClientEngine
import io.github.hospes.plexify.web.imdb.model.ImdbSearchResponse
import io.github.hospes.plexify.web.nonstrict
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*

object ImdbApi : MediaServiceApi {

    private val httpClient by lazy {
        HttpClient(createHttpClientEngine()) {
            install(ContentNegotiation) { json(nonstrict) }

            defaultRequest {
                url("https://api.imdbapi.dev")
            }
        }
    }

    // Since we plan to use pirate(free) version of imdb api we can hardcode true here
    // [ Base URL: api.imdbapi.dev ] -> https://imdbapi.dev/
    override fun isApiKeyAvailable(): Boolean = true

    suspend fun search(title: String) = httpClient.get("/search/titles") {
        contentType(ContentType.Application.Json)
        parameter("query", title)
        parameter("limit", 2)
    }.body<ImdbSearchResponse>()
}