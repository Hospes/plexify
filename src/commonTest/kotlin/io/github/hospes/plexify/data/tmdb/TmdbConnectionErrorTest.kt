package io.github.hospes.plexify.data.tmdb

import io.ktor.client.engine.mock.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TmdbConnectionErrorTest {

    private val credentials = TmdbCredentials(apiKey = "secret-key", accessToken = null, source = TmdbCredentials.Source.USER)

    private fun provider(handler: MockRequestHandler) =
        TmdbProvider(credentials) { MockEngine(handler) }

    @Test
    fun `a connection error fails with curl's reason instead of the request url`() = runBlocking {
        val provider = provider { request ->
            // As the curl engine words it.
            throw IllegalStateException(
                "Connection failed for request: CurlRequestData(url='${request.url}', method='GET', content: 0 bytes). " +
                        "Reason: Could not connect to server (CURLE_COULDNT_CONNECT)"
            )
        }

        val error = provider.search("Dune", null).exceptionOrNull()

        assertEquals("TMDB request failed searching for 'Dune': Could not connect to server (CURLE_COULDNT_CONNECT)", error?.message)
        val printed = error!!.stackTraceToString()
        assertFalse("secret-key" in printed, printed)
        assertFalse("api.themoviedb.org" in printed, printed)
    }

    @Test
    fun `an error without curl's wording keeps its message`() = runBlocking {
        val provider = provider { throw IllegalStateException("Network is unreachable") }

        val error = provider.search("Dune", null).exceptionOrNull()

        assertEquals("TMDB request failed searching for 'Dune': Network is unreachable", error?.message)
    }
}
