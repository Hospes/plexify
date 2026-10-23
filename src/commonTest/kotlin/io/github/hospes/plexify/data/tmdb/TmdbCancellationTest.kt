package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cancelling the coroutine that runs a lookup must cancel the lookup, not end it in a failure value: the
 * caller would go on as if TMDB had failed, counting the file as failed. Ktor's own request timeout stays a
 * [io.github.hospes.plexify.data.MetadataTimeoutException] (see TmdbTimeoutTest).
 */
class TmdbCancellationTest {

    private val credentials = TmdbCredentials(apiKey = "secret-key", accessToken = null, source = TmdbCredentials.Source.USER)
    // Long enough that Ktor's own timeout never fires before the test cancels.
    private val timeouts = TmdbTimeouts(connectMillis = 5_000, requestMillis = 5_000, retries = 0, optionalRequestMillis = 5_000, unreachableAfter = 3)
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val show = CanonicalMedia.TvShow(title = "Ghosts", year = 2019, tmdbId = "1")

    private fun provider(handler: MockRequestHandler) = TmdbProvider(credentials, timeouts) { MockEngine(handler) }

    private fun capped(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000, block) }

    private val lookups: Map<String, suspend TmdbProvider.() -> Result<*>> = mapOf(
        "verifyCredentials" to { verifyCredentials() },
        "search" to { search("Ghosts", null) },
        "searchMovies" to { searchMovies("Dune", "2021") },
        "season" to { season(show, 4) },
        "episode" to { episode(show, 4, 1) },
        "episodeGroups" to { episodeGroups(show) },
        "externalIds" to { externalIds(show) },
    )

    /** Starts [lookup], cancels it once a request is waiting on TMDB, and returns what the lookup returned, if anything. */
    private suspend fun CoroutineScope.cancelWhileWaiting(
        provider: TmdbProvider,
        requestStarted: CompletableDeferred<Unit>,
        lookup: suspend TmdbProvider.() -> Result<*>,
    ): Result<*>? {
        var returned: Result<*>? = null
        val job = launch { returned = provider.lookup() }
        requestStarted.await()
        job.cancelAndJoin()
        return returned
    }

    @Test
    fun `cancelling any lookup cancels it instead of returning a failure`() = capped {
        for ((name, lookup) in lookups) {
            val requestStarted = CompletableDeferred<Unit>()
            val provider = provider {
                requestStarted.complete(Unit)
                awaitCancellation()
            }

            val returned = cancelWhileWaiting(provider, requestStarted, lookup)

            assertNull(returned, "$name returned $returned")
        }
    }

    @Test
    fun `a timeout around a search ends it with that timeout`() = capped {
        val provider = provider { awaitCancellation() }
        var returned: Result<*>? = null

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) { returned = provider.search("Ghosts", null) }
        }

        assertNull(returned)
    }

    @Test
    fun `cancelling an alternative titles lookup cancels the search`() = capped {
        val requestStarted = CompletableDeferred<Unit>()
        val provider = provider { request ->
            if (request.url.encodedPath.endsWith("search/multi")) {
                respond(
                    """{"results": [{"media_type": "tv", "id": 85937, "name": "Demon Slayer", "original_name": "鬼滅の刃", "first_air_date": "2019-04-06"}]}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
            } else {
                requestStarted.complete(Unit)
                awaitCancellation()
            }
        }

        val returned = cancelWhileWaiting(provider, requestStarted) { search("Kimetsu no Yaiba", null) }

        assertNull(returned)
    }

    @Test
    fun `cancelled requests do not count toward tmdb being unreachable`() = capped {
        var answering = false
        var requests = 0
        var requestStarted = CompletableDeferred<Unit>()
        val provider = provider {
            requests++
            if (answering) return@provider respond("""{"results": []}""", HttpStatusCode.OK, jsonHeaders)
            requestStarted.complete(Unit)
            awaitCancellation()
        }

        repeat(timeouts.unreachableAfter) {
            requestStarted = CompletableDeferred()
            cancelWhileWaiting(provider, requestStarted) { search("Ghosts", null) }
        }
        answering = true
        val result = provider.search("Dune", null)

        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        assertEquals(timeouts.unreachableAfter + 1, requests)
    }
}
