package io.github.hospes.plexify.data.tmdb

import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** A request TMDB still rate-limits after the retries (TmdbRetryDelayTest has those) fails with a message for whose key it is. */
class TmdbRateLimitTest {

    private val builtIn = TmdbCredentials(apiKey = null, accessToken = "built-in-token", source = TmdbCredentials.Source.BUILT_IN)

    // TMDB's body for HTTP 429.
    private val rateLimited = """{"success":false,"status_code":25,"status_message":"Your request count (41) is over the allowed limit of (40)."}"""

    private fun searchError(credentials: TmdbCredentials): String? = runBlocking {
        val provider = mockProvider(TmdbTimeouts(retryDelay = {}), credentials) { respondJson(rateLimited, HttpStatusCode.TooManyRequests) }
        provider.search("Dune", null).exceptionOrNull()?.message
    }

    @Test
    fun `the built-in key's rate limit tells the user to set their own`() {
        assertEquals(
            "TMDB rate limit reached (HTTP 429) searching for 'Dune'. The built-in key is shared by all Plexify users; " +
                "set your own with TMDB_API_ACCESS_TOKEN (see README).",
            searchError(builtIn),
        )
    }

    @Test
    fun `the user's own rate limit is a plain HTTP error`() {
        assertEquals("HTTP 429 searching for 'Dune'", searchError(tokenCredentials))
    }
}
