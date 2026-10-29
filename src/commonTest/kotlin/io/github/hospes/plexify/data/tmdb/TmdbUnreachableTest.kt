package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.MetadataConnectionException
import io.github.hospes.plexify.data.MetadataTimeoutException
import io.github.hospes.plexify.data.MetadataUnreachableException
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

// Real time, as in TmdbTimeoutTest. No retry after a timeout here, so each request costs one timeout.
class TmdbUnreachableTest {

    private val timeouts = TmdbTimeouts(connectMillis = 200, requestMillis = 200, retries = 0, optionalRequestMillis = 200, unreachableAfter = 3)

    private fun refused(): Nothing = throw IllegalStateException("Connection failed for request: <request>. Reason: Could not connect to server")

    @Test
    fun `after three requests in a row time out the next fails without a request`() = capped {
        var calls = 0
        val provider = mockProvider(timeouts) {
            calls++
            awaitCancellation()
        }

        assertIs<MetadataTimeoutException>(provider.search("Ghosts", null).exceptionOrNull())
        assertIs<MetadataTimeoutException>(provider.search("Ghosts", null).exceptionOrNull())
        val third = provider.search("Ghosts", null).exceptionOrNull()
        val start = TimeSource.Monotonic.markNow()
        val fourth = provider.search("Dune", null).exceptionOrNull()

        assertIs<MetadataUnreachableException>(third)
        assertEquals(
            "TMDB request timed out searching for 'Ghosts'; TMDB stopped answering (3 requests in a row), so later lookups in this run fail at once",
            third.message,
        )
        assertIs<MetadataUnreachableException>(fourth)
        assertEquals("Not searching for 'Dune': TMDB stopped answering earlier in this run", fourth.message)
        assertEquals(3, calls)
        assertTrue(start.elapsedNow() < 100.milliseconds)
    }

    @Test
    fun `connection failures count as no answer`() = capped {
        var calls = 0
        val provider = mockProvider(timeouts) {
            calls++
            refused()
        }

        assertIs<MetadataConnectionException>(provider.search("Ghosts", null).exceptionOrNull())
        assertIs<MetadataConnectionException>(provider.search("Ghosts", null).exceptionOrNull())
        assertIs<MetadataUnreachableException>(provider.search("Ghosts", null).exceptionOrNull())
        assertIs<MetadataUnreachableException>(provider.verifyCredentials().exceptionOrNull())
        assertEquals(3, calls)
    }

    @Test
    fun `an answer in between starts the count again`() = capped {
        var calls = 0
        val provider = mockProvider(timeouts) {
            calls++
            if (calls == 3) respondJson("""{"results": []}""") else refused()
        }

        repeat(5) { provider.search("Ghosts", null) }
        val sixth = provider.search("Ghosts", null).exceptionOrNull()

        // Two failures, an answer, then three in a row: the sixth request is the third.
        assertIs<MetadataUnreachableException>(sixth)
        assertEquals(6, calls)
    }

    @Test
    fun `an HTTP error is an answer`() = capped {
        var calls = 0
        val provider = mockProvider(timeouts) {
            calls++
            if (calls == 3) respond("", HttpStatusCode.ServiceUnavailable) else refused()
        }

        repeat(4) { provider.search("Ghosts", null) }
        val fifth = provider.search("Ghosts", null).exceptionOrNull()

        // Two failures, an HTTP 503, then two more: the 503 started the count again.
        assertIs<MetadataConnectionException>(fifth)
        assertEquals(5, calls)
    }

    private fun tvResult(id: Int, name: String) =
        """{"media_type": "tv", "id": $id, "name": "$name", "original_name": "$name", "first_air_date": "2020-01-01"}"""

    /** A search answering [results], whose alternative-titles lookups stall; records those lookups. */
    private fun stalledAltTitles(results: String, altTitleCalls: MutableList<String>) =
        mockProvider(timeouts.copy(requestMillis = 5_000, optionalRequestMillis = 200, retries = 1)) { request ->
            if ("alternative_titles" in request.url.encodedPath) {
                altTitleCalls += request.url.encodedPath
                awaitCancellation()
            }
            respondJson("""{"results": [$results]}""")
        }

    @Test
    fun `a stalled alternative-titles lookup is cut short and fails a search with no convincing result`() = capped {
        // Three unconvincing results, so each would get an alternative-titles lookup; the alias may be the match.
        val altTitleCalls = mutableListOf<String>()
        val provider = stalledAltTitles((1..3).joinToString(",") { tvResult(it, "Unrelated $it") }, altTitleCalls)

        val start = TimeSource.Monotonic.markNow()
        val error = provider.searchWithAliases("Kimetsu no Yaiba").exceptionOrNull()

        assertIs<MetadataTimeoutException>(error)
        assertEquals("TMDB request timed out fetching alternative titles of 'Unrelated 1'", error.message)
        // One lookup with the short limit and no retry, not three with the full one.
        assertEquals(1, altTitleCalls.size)
        assertTrue(start.elapsedNow() < 2_000.milliseconds, "took ${start.elapsedNow()}")
    }

    @Test
    fun `a stalled alternative-titles lookup leaves a search with a convincing result as it is`() = capped {
        val altTitleCalls = mutableListOf<String>()
        val provider = stalledAltTitles(tvResult(1, "Kimetsu no Yaiba") + "," + tvResult(2, "Unrelated 2") + "," + tvResult(3, "Unrelated 3"), altTitleCalls)

        val found = provider.searchWithAliases("Kimetsu no Yaiba").getOrThrow()

        assertEquals(3, found.size)
        assertTrue(found.all { it is MediaSearchResult.TvShow && it.alternativeTitles.isEmpty() })
        assertEquals(1, altTitleCalls.size)
    }
}
