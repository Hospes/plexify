package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.tmdb.TmdbTimeouts
import io.github.hospes.plexify.data.tmdb.capped
import io.github.hospes.plexify.data.tmdb.mockProvider
import io.github.hospes.plexify.data.tmdb.respondJson
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A search whose deciding alias lookup fails with an HTTP error, through the real provider (issue #136): the file
 * fails, as for any failed search, instead of being skipped as "no match" with its show cached as a miss.
 */
class AliasLookupFailureTest {

    @Test
    fun `episodes whose show search fails on an alias lookup count as failed and search again`() = capped {
        val searches = mutableListOf<String>()
        val provider = mockProvider(TmdbTimeouts(retryDelay = {})) { request ->
            when (request.url.encodedPath) {
                "/3/search/multi" -> {
                    searches += request.url.parameters["query"].orEmpty()
                    respondJson("""{"results": [{"media_type": "tv", "id": 1, "name": "Black Seraph", "original_name": "Black Seraph", "first_air_date": "2000-10-03"}]}""")
                }

                "/3/tv/1/alternative_titles" -> respondJson("""{"success":false,"status_code":11}""", HttpStatusCode.InternalServerError)
                else -> error("Unexpected request ${request.url.encodedPath}")
            }
        }

        val run = runProcessor(provider, "Dark.Angel.S01E01.1080p.WEB.mkv", "Dark.Angel.S01E02.1080p.WEB.mkv")

        assertEquals(2, run.stats.failed)
        assertEquals(0, run.stats.skipped)
        // A failed search says nothing about the show, so it is not cached as a miss.
        assertEquals(2, searches.size)
        assertEquals(
            listOf(
                "✗ Dark.Angel.S01E01.1080p.WEB.mkv — HTTP 500 fetching alternative titles of 'Black Seraph'",
                "✗ Dark.Angel.S01E02.1080p.WEB.mkv — HTTP 500 fetching alternative titles of 'Black Seraph'",
            ),
            run.output.filter { it.trimStart().startsWith("✗") }.map { it.trim() },
        )
    }
}
