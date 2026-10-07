package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.MetadataTimeoutException
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Real time: Ktor's timeout and retry delays run outside a test scheduler, so keep the limits short.
class TmdbTimeoutTest {

    private val credentials = TmdbCredentials(apiKey = "secret-key", accessToken = null, source = TmdbCredentials.Source.USER)
    private val timeouts = TmdbTimeouts(connectMillis = 200, requestMillis = 200, retries = 1)
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun provider(handler: MockRequestHandler) =
        TmdbProvider(credentials, timeouts) { MockEngine(handler) }

    // A regression should fail the test, not hang the build.
    private fun capped(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000, block) }

    @Test
    fun `a stalled request fails with a short message after one retry`() = capped {
        var calls = 0
        val provider = provider {
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
        val provider = provider {
            val body = ByteChannel()
            body.writeStringUtf8("""{"results": [""")
            body.flush()
            // The handler runs in a child of the call's job. Close the body when the call is cancelled,
            // as the curl engine does; MockEngine leaves it open.
            @OptIn(ExperimentalCoroutinesApi::class)
            currentCoroutineContext()[Job]?.parent?.invokeOnCompletion { cause -> body.cancel(cause) }
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }

        val error = provider.search("Dune", null).exceptionOrNull()

        assertIs<MetadataTimeoutException>(error)
    }

    @Test
    fun `the timeout error does not leak the request url or api key`() = capped {
        val provider = provider { awaitCancellation() }

        val error = provider.search("Dune", null).exceptionOrNull()

        assertIs<MetadataTimeoutException>(error)
        val printed = error.stackTraceToString()
        assertFalse("secret-key" in printed, printed)
        assertFalse("api.themoviedb.org" in printed, printed)
    }

    @Test
    fun `the credentials check times out instead of hanging`() = capped {
        val provider = provider { awaitCancellation() }

        val error = provider.verifyCredentials().exceptionOrNull()

        assertIs<MetadataTimeoutException>(error)
        assertEquals("TMDB request timed out verifying credentials", error.message)
    }

    @Test
    fun `a rate-limited request is still retried`() = capped {
        var calls = 0
        val provider = provider {
            calls++
            if (calls == 1) {
                respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "0"))
            } else {
                respond("""{"results": []}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val result = provider.search("Dune", null)

        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        assertEquals(2, calls)
    }
}
