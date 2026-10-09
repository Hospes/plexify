package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.MediaSearchResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SeasonFailureTest {

    private val ghosts = listOf(MediaSearchResult.TvShow(title = "Ghosts", year = "2021", tmdbId = "1", provider = "TMDb", matchConfidence = 100.0))

    @Test
    fun `episodes of a season that fails to load count as failed and fetch it again`() = runTest {
        val provider = FakeTmdb(ghosts, seasons = mapOf("1" to mapOf(4 to 1..8)), failingSeasons = setOf("1:4"))

        val run = runProcessor(provider, "Ghosts.S04E01.1080p.WEB.mkv", "Ghosts.S04E02.1080p.WEB.mkv")

        assertEquals(2, run.stats.failed)
        assertEquals(0, run.stats.skipped)
        // A failed fetch says nothing about the season, so it is not cached as empty.
        assertEquals(listOf("1:4", "1:4"), run.provider.seasonFetches)
    }

    @Test
    fun `a season that loads on a later file is used for it`() = runTest {
        val provider = FakeTmdb(ghosts, seasons = mapOf("1" to mapOf(4 to 1..8)), failingSeasons = setOf("1:4"), seasonFailures = 1)

        val run = runProcessor(provider, "Ghosts.S04E01.1080p.WEB.mkv", "Ghosts.S04E02.1080p.WEB.mkv")

        assertEquals(1, run.stats.failed)
        assertEquals(1, run.stats.organized)
        assertNotNull(run.episodeOf("Ghosts.S04E02.1080p.WEB.mkv"))
    }

    @Test
    fun `a missing season is still skipped`() = runTest {
        val provider = FakeTmdb(ghosts, seasons = mapOf("1" to mapOf(1 to 1..8)))

        val run = runProcessor(provider, "Ghosts.S04E01.1080p.WEB.mkv")

        assertEquals(0, run.stats.failed)
        assertEquals(1, run.stats.skipped)
    }
}
