package io.github.hospes.plexify.web.tmdb

import io.github.hospes.plexify.BuildConfig
import io.github.hospes.plexify.web.MediaServiceApi
import io.github.hospes.plexify.web.createHttpClientEngine
import io.github.hospes.plexify.web.nonstrict
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

object TmdbApi : MediaServiceApi {

    private val httpClient by lazy {
        HttpClient(createHttpClientEngine()) {
            install(ContentNegotiation) { json(nonstrict) }
        }
    }


    override fun isApiKeyAvailable(): Boolean = BuildConfig.TMDB_API_KEY.isNotBlank() || BuildConfig.TMDB_API_READ_ACCESS_TOKEN.isNotBlank()
}