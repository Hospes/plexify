package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.tmdb.TmdbTimeouts
import io.github.hospes.plexify.data.tmdb.capped
import io.github.hospes.plexify.data.tmdb.mockProvider
import io.github.hospes.plexify.data.tmdb.respondJson
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A TMDB that stops answering mid-run, through the real provider: the run must not wait it out for every file. */
class TmdbOutageTest {

    private val timeouts = TmdbTimeouts(connectMillis = 200, requestMillis = 200, retries = 0, optionalRequestMillis = 200, unreachableAfter = 3)

    private fun showJson(id: Int, name: String, originalName: String = name) =
        """{"results": [{"media_type": "tv", "id": $id, "name": "$name", "original_name": "$originalName", "first_air_date": "2019-04-06"}]}"""

    // Before the breaker, a run waited out a timeout for every file; a regression fails here instead of crawling.
    private val limitMillis = 5_000L

    @Test
    fun `when tmdb stops answering only the first files wait and every file counts as failed`() = capped(limitMillis) {
        var requests = 0
        val provider = mockProvider(timeouts) {
            requests++
            awaitCancellation()
        }
        val fileNames = (1..10).map { "Ghosts.S04E${it.toString().padStart(2, '0')}.1080p.WEB.mkv" } + "Dune.Part.Two.2024.1080p.WEB-DL.mkv"

        val run = runProcessor(provider, *fileNames.toTypedArray())

        assertEquals(3, requests)
        assertEquals(11, run.stats.failed)
        assertEquals(0, run.stats.skipped)
        assertEquals(0, run.stats.organized)
    }

    @Test
    fun `episodes of a season looked up before the outage are still organized`() = capped(limitMillis) {
        var requests = 0
        val provider = mockProvider(timeouts) { request ->
            requests++
            when {
                request.url.encodedPath.endsWith("search/multi") && request.url.parameters["query"] == "ghosts" ->
                    respondJson(showJson(1, "Ghosts"))

                request.url.encodedPath.endsWith("tv/1/season/4") -> respondJson(
                    """{"id": 14, "name": "Season 4", "season_number": 4, "episodes": [""" +
                            (1..10).joinToString(",") { """{"id": $it, "name": "Episode $it", "episode_number": $it, "season_number": 4}""" } + "]}",
                )

                else -> awaitCancellation()
            }
        }

        val run = runProcessor(
            provider,
            "Ghosts.S04E01.1080p.WEB.mkv", "Ghosts.S04E02.1080p.WEB.mkv", "Ghosts.S04E03.1080p.WEB.mkv",
            "Severance.S02E01.1080p.WEB.mkv", "Severance.S02E02.1080p.WEB.mkv",
            "Silo.S02E01.1080p.WEB.mkv", "The.Bear.S03E01.1080p.WEB.mkv",
        )

        // Search and season for Ghosts, then three unanswered searches; The Bear isn't tried.
        assertEquals(5, requests)
        (1..3).forEach { assertNotNull(run.episodeOf("Ghosts.S04E0$it.1080p.WEB.mkv")) }
        assertEquals(3, run.stats.organized)
        assertEquals(4, run.stats.failed)
        assertEquals(0, run.stats.skipped)
    }

    @Test
    fun `a show found only by an alias whose lookup gets no answer fails instead of being skipped`() = capped(limitMillis) {
        var requests = 0
        val provider = mockProvider(timeouts) { request ->
            requests++
            if (request.url.encodedPath.endsWith("search/multi")) {
                respondJson(showJson(85937, "Demon Slayer", "鬼滅の刃"))
            } else {
                awaitCancellation()
            }
        }

        val run = runProcessor(provider, *(1..4).map { "Kimetsu.no.Yaiba.S01E0$it.1080p.WEB.mkv" }.toTypedArray())

        // The failed search isn't cached as a miss, so every file searches again: the search answers (so the
        // breaker stays closed) and one alias lookup waits out the short limit.
        assertEquals(4, run.stats.failed)
        assertEquals(0, run.stats.skipped)
        assertEquals(8, requests)
    }
}
