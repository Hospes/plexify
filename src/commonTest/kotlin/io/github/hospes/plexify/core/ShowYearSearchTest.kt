package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.MediaSearchResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// A bare year closing a scene show name is the show's year ("Doctor.Who.2005.S01E01"), or part of its
// title ("Space.1999.S01E01").
class ShowYearSearchTest {

    private fun show(title: String, year: String, tmdbId: String) =
        MediaSearchResult.TvShow(title = title, year = year, tmdbId = tmdbId, provider = "TMDb", matchConfidence = 100.0)

    private val seasons = mapOf("57243" to mapOf(1 to 1..13), "121" to mapOf(1 to 1..42), "1" to mapOf(1 to 1..24))

    @Test
    fun `a bare year picks the show from that year`() = runTest {
        val provider = FakeTmdb(listOf(show("Doctor Who", "1963", "121"), show("Doctor Who", "2005", "57243")), seasons = seasons)

        val run = runProcessor(provider, "Doctor.Who.2005.S01E01.Rose.1080p.mkv")

        assertEquals("57243", run.showOf("Doctor.Who.2005.S01E01.Rose.1080p.mkv"))
        assertEquals(listOf("doctor who"), run.provider.searchedTitles)
    }

    @Test
    fun `a bare year that belongs to the title is matched as part of it`() = runTest {
        val provider = FakeTmdb(
            results = emptyList(),
            seasons = seasons,
            resultsByTitle = mapOf("space" to listOf(show("Space: 1999", "1975", "1")), "space 1999" to listOf(show("Space: 1999", "1975", "1"))),
        )

        val run = runProcessor(provider, "Space.1999.S01E01.Breakaway.mkv", "Space.1999.S01E02.Matter.of.Life.and.Death.mkv")

        assertEquals("1", run.showOf("Space.1999.S01E01.Breakaway.mkv"))
        assertEquals("1", run.showOf("Space.1999.S01E02.Matter.of.Life.and.Death.mkv"))
        // Once per show: the second file reuses the cached match
        assertEquals(listOf("space", "space 1999"), run.provider.searchedTitles)
    }

    @Test
    fun `a show whose title lacks the year is not matched by the title reading`() = runTest {
        val provider = FakeTmdb(
            results = emptyList(),
            seasons = seasons,
            resultsByTitle = mapOf("space 1999" to listOf(show("Space", "2001", "1"))),
        )

        val run = runProcessor(provider, "Space.1999.S01E01.Breakaway.mkv")

        assertEquals(null, run.showOf("Space.1999.S01E01.Breakaway.mkv"))
        assertEquals(1, run.stats.skipped)
    }

    @Test
    fun `a year after the episode marker is never read as part of the title`() = runTest {
        val provider = FakeTmdb(listOf(show("The Boys", "2019", "1")), seasons = mapOf("1" to mapOf(3 to 1..8)))

        val run = runProcessor(provider, "The.Boys.S03E01.2022.720p.mkv")

        assertEquals("1", run.showOf("The.Boys.S03E01.2022.720p.mkv"))
        assertEquals(listOf("the boys"), run.provider.searchedTitles)
    }

    @Test
    fun `a year set by the user is never read as part of the title`() = runTest {
        val provider = FakeTmdb(
            results = emptyList(),
            seasons = seasons,
            resultsByTitle = mapOf("space 1999" to listOf(show("Space: 1999", "1975", "1"))),
        )

        val run = runProcessor(provider, "Space.1999.S01E01.Breakaway.mkv", yearOverride = "1999")

        assertEquals(listOf("space"), run.provider.searchedTitles)
    }
}
