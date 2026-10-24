package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.MetadataTimeoutException
import io.ktor.client.engine.mock.*
import io.ktor.client.network.sockets.*
import io.ktor.http.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

// The back-off of the default TmdbTimeouts, recorded instead of waited out: other tests skip it.
class TmdbRetryDelayTest {

    private val credentials = TmdbCredentials(apiKey = "secret-key", accessToken = null, source = TmdbCredentials.Source.USER)
    private val waits = mutableListOf<Long>()

    private fun provider(handler: MockRequestHandler) =
        TmdbProvider(credentials, TmdbTimeouts(retryDelay = { waits += it })) { MockEngine(handler) }

    private fun assertWait(expected: LongRange, actual: Long) = assertTrue(actual in expected, "waited $actual ms, expected $expected")

    @Test
    fun `a rate-limited request backs off exponentially before each of three retries`() = runBlocking {
        var calls = 0
        val provider = provider {
            calls++
            respond("", HttpStatusCode.TooManyRequests)
        }

        assertTrue(provider.search("Dune", null).isFailure)
        assertEquals(4, calls)
        assertEquals(3, waits.size)
        assertWait(1_000L..2_000L, waits[0])
        assertWait(2_000L..3_000L, waits[1])
        assertWait(4_000L..5_000L, waits[2])
    }

    @Test
    fun `a longer retry-after header is waited out instead`() = runBlocking {
        var calls = 0
        val provider = provider {
            calls++
            if (calls == 1) respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "10"))
            else respond("""{"results": []}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }

        assertTrue(provider.search("Dune", null).isSuccess)
        assertEquals(listOf(10_000L), waits)
    }

    @Test
    fun `a timeout is retried once after a back-off of one to two seconds`() = runBlocking {
        var calls = 0
        val provider = provider {
            calls++
            throw ConnectTimeoutException("connect timed out")
        }

        assertIs<MetadataTimeoutException>(provider.search("Dune", null).exceptionOrNull())
        assertEquals(2, calls)
        assertEquals(1, waits.size)
        assertWait(1_000L..2_000L, waits[0])
    }

    // The default wait itself, as #34 and #89 need it: a cancelled run must not sit out the back-off.
    @Test
    fun `cancelling during the back-off stops the wait`() = runBlocking {
        var calls = 0
        var returned = false
        val provider = TmdbProvider(credentials) {
            MockEngine {
                calls++
                respond("", HttpStatusCode.TooManyRequests)
            }
        }
        val job = launch {
            provider.search("Dune", null)
            returned = true
        }
        while (calls == 0) delay(10)
        delay(100) // into the 1-2 s wait
        val cancelled = TimeSource.Monotonic.markNow()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(1, calls)
        assertTrue(cancelled.elapsedNow() < 500.milliseconds, "took ${cancelled.elapsedNow()} to stop")
    }
}
