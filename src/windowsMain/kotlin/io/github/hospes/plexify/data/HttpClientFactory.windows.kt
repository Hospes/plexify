package io.github.hospes.plexify.data

import io.ktor.client.engine.*
import io.ktor.client.engine.curl.*

// Ktor's libcurl uses Schannel on Windows, which verifies against the Windows certificate store.
actual fun createHttpClientEngine(): HttpClientEngine = Curl.create()
