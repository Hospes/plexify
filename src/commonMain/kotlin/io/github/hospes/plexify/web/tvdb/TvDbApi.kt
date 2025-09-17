package io.github.hospes.plexify.web.tvdb

import io.github.hospes.plexify.BuildConfig
import io.github.hospes.plexify.web.MediaServiceApi
import io.github.hospes.plexify.web.createHttpClientEngine
import io.github.hospes.plexify.web.nonstrict
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*

object TvDbApi : MediaServiceApi {

    private val httpClient by lazy {
        HttpClient(createHttpClientEngine()) {
            install(ContentNegotiation) { json(nonstrict) }
        }
    }


    override fun isApiKeyAvailable(): Boolean = BuildConfig.TVDB_API_KEY.isNotBlank()
}