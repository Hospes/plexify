package io.github.hospes.plexify.web.api

import io.github.hospes.plexify.BuildConfig
import io.github.hospes.plexify.web.createHttpClientEngine
import io.github.hospes.plexify.web.nonstrict
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*

object TmdbApi : MediaServiceApi {

    private val httpClient by lazy {
        HttpClient(createHttpClientEngine()) {
            install(ContentNegotiation) { json(nonstrict) }
        }
    }


    override fun isApiKeyAvailable(): Boolean = BuildConfig.TMDB_API_KEY.isNotBlank() || BuildConfig.TMDB_API_READ_ACCESS_TOKEN.isNotBlank()
}