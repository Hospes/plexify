package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.MetadataTimeoutException
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Real time: Ktor's timeout and retry delays run outside a test scheduler, so keep the limits short.
class TmdbTimeoutTest {

    private val timeouts = TmdbTimeouts(connectMillis = 200, requestMillis = 200, retries = 1)

    @Test
    fun `a stalled request fails with a short message after one retry`() = capped {
        var calls = 0
        val provider = mockProvider(timeouts) {
            calls++
            awaitCancellation()
        }

        val error = provider.search("Dune", null).exceptionOrNull()

        assertIs<MetadataTimeoutException>(error)
        assertEquals("TMDB request timed out searching for 'Dune'", error.message)
        assertEquals(2, calls)
    }

    @Test
    fun `a body that stops arriving times out`() = capped {
        val provider = mockProvider(timeouts) { respondJson(stalledBody("""{"results": [""")) }

        val error = provider.search("Dune", null).exceptionOrNull()

        assertIs<MetadataTimeoutException>(error)
    }

    @Test
    fun `the timeout error does not leak the request url or api key`() = capped {
        val provider = mockProvider(timeouts, credentials = keyCredentials) { awaitCancellation() }

        val error = provider.search("Dune", null).exceptionOrNull()

        assertIs<MetadataTimeoutException>(error)
        val printed = error.stackTraceToString()
        assertFalse(keyCredentials.apiKey!! in printed, printed)
        assertFalse("api.themoviedb.org" in printed, printed)
    }

    @Test
    fun `the credentials check times out instead of hanging`() = capped {
        val provider = mockProvider(timeouts) { awaitCancellation() }

        val error = provider.verifyCredentials().exceptionOrNull()

        assertIs<MetadataTimeoutException>(error)
        assertEquals("TMDB request timed out verifying credentials", error.message)
    }

    @Test
    fun `a rate-limited request is still retried`() = capped {
        var calls = 0
        val provider = mockProvider(timeouts) {
            calls++
            if (calls == 1) {
                respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "0"))
            } else {
                respondJson("""{"results": []}""")
            }
        }

        val result = provider.search("Dune", null)

        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        assertEquals(2, calls)
    }
}
