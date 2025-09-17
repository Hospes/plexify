package io.github.hospes.plexify.data

import io.ktor.client.engine.*
import io.ktor.client.engine.curl.*

actual fun createHttpClientEngine(): HttpClientEngine = Curl.create { sslVerify = false }