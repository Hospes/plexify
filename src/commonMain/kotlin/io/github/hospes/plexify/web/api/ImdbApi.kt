package io.github.hospes.plexify.web.api

import io.github.hospes.plexify.web.createHttpClientEngine
import io.github.hospes.plexify.web.nonstrict
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*

object ImdbApi : MediaServiceApi {

    private val httpClient by lazy {
        HttpClient(createHttpClientEngine()) {
            install(ContentNegotiation) { json(nonstrict) }
        }
    }

    // Since we plan to use pirate(free) version of imdb api we can hardcode true here
    // [ Base URL: api.imdbapi.dev ] -> https://imdbapi.dev/
    override fun isApiKeyAvailable(): Boolean = true
}