package io.github.hospes.plexify.web.tvdb

import io.github.hospes.plexify.web.MediaServiceApi
import io.github.hospes.plexify.web.createHttpClientEngine
import io.github.hospes.plexify.web.nonstrict
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*

class TvDbApi(
    private val apiKey: String,
) : MediaServiceApi {

    private val httpClient by lazy {
        HttpClient(createHttpClientEngine()) {
            install(ContentNegotiation) { json(nonstrict) }
        }
    }
}