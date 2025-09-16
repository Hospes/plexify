package io.github.hospes.plexify.web

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*

expect fun createHttpClientEngine(): HttpClientEngineFactory<*>

val httpClient by lazy {
    HttpClient(createHttpClientEngine()) {
        install(ContentNegotiation) {
            json()
        }
    }
}