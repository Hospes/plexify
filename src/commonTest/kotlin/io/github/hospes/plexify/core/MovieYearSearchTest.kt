package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.MetadataTimeoutException
import io.github.hospes.plexify.domain.model.MediaSearchResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// TMDB results as seen live (October 2026): the first page of "dracula" or "pinocchio" lacks the
// 1974 and 2012 films, which only the search with the year returns.
class MovieYearSearchTest {

    private fun movie(title: String, year: String, tmdbId: String) =
        MediaSearchResult.Movie(title = title, year = year, tmdbId = tmdbId, provider = "TMDb", matchConfidence = 100.0)

    private val dracula = listOf(movie("Dracula", "1931", "138"), movie("Dracula", "2025", "1246049"))
    private val dracula1974 = listOf(
        movie("Countess Dracula", "1971", "39043").copy(matchConfidence = 50.0),
        movie("Dracula", "1974", "86889"),
    )

    @Test
    fun `a film missing from the first results is found by searching with the year`() = runTest {
        val run = runProcessor(FakeTmdb(dracula, moviesByYear = mapOf("1974" to dracula1974)), "Dracula.1974.1080p.BluRay.mkv")

        assertEquals("86889", run.movieOf("Dracula.1974.1080p.BluRay.mkv"))
        assertEquals(listOf("1974"), run.provider.yearSearches)
    }

    @Test
    fun `the film from the filename year beats one a year off`() = runTest {
        val provider = FakeTmdb(
            results = listOf(movie("Pinocchio", "2013", "246790")),
            moviesByYear = mapOf("2012" to listOf(movie("Pinocchio", "1940", "10895"), movie("Pinocchio", "2012", "136619"))),
        )

        val run = runProcessor(provider, "Pinocchio.2012.1080p.BluRay.mkv")

        assertEquals("136619", run.movieOf("Pinocchio.2012.1080p.BluRay.mkv"))
    }

    @Test
    fun `no second search when the first results have the film from that year`() = runTest {
        val run = runProcessor(FakeTmdb(listOf(movie("Hamlet", "1996", "10549"), movie("Hamlet", "1990", "10264"))), "Hamlet.1990.1080p.mkv")

        assertEquals("10264", run.movieOf("Hamlet.1990.1080p.mkv"))
        assertEquals(emptyList(), run.provider.yearSearches)
    }

    @Test
    fun `no second search without a year`() = runTest {
        val run = runProcessor(FakeTmdb(listOf(movie("Hamlet", "1996", "10549"))), "Hamlet.1080p.mkv")

        assertEquals("10549", run.movieOf("Hamlet.1080p.mkv"))
        assertEquals(emptyList(), run.provider.yearSearches)
    }

    @Test
    fun `a film of the same title from another year is skipped when the year search finds nothing`() = runTest {
        // The parser reads "Blade.Runner.2049" as Blade Runner from 2049 (#46).
        val run = runProcessor(FakeTmdb(listOf(movie("Blade Runner", "1982", "78"))), "Blade.Runner.2049.1080p.mkv")

        assertNull(run.movieOf("Blade.Runner.2049.1080p.mkv"))
        assertEquals(1, run.stats.skipped)
    }

    @Test
    fun `a failed year search keeps the match from the first results`() = runTest {
        val provider = FakeTmdb(
            results = listOf(movie("Pinocchio", "2013", "246790")),
            yearSearchFailure = MetadataTimeoutException("TMDB request timed out searching"),
        )

        val run = runProcessor(provider, "Pinocchio.2012.1080p.BluRay.mkv")

        assertEquals("246790", run.movieOf("Pinocchio.2012.1080p.BluRay.mkv"))
        assertEquals(0, run.stats.failed)
    }

    @Test
    fun `a failed year search with no other candidate counts as failed`() = runTest {
        val provider = FakeTmdb(dracula, yearSearchFailure = MetadataTimeoutException("TMDB request timed out searching"))

        val run = runProcessor(provider, "Dracula.1974.1080p.BluRay.mkv")

        assertNull(run.movieOf("Dracula.1974.1080p.BluRay.mkv"))
        assertEquals(1, run.stats.failed)
    }
}
