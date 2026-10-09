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
    fun `a film of the same title from another year is skipped when no search finds that year`() = runTest {
        val provider = FakeTmdb(listOf(movie("Blade Runner", "1982", "78")))

        val run = runProcessor(provider, "Blade.Runner.1980.1080p.mkv")

        // Searched as "blade runner 1980" too, where the 1982 film has no year to contradict, but its
        // title doesn't have the year.
        assertEquals(listOf("blade runner", "blade runner 1980"), run.provider.searchedTitles)
        assertNull(run.movieOf("Blade.Runner.1980.1080p.mkv"))
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

    // "Wonder.Woman.1984.1080p": the 1984 is the title's, and no Wonder Woman film is from 1984. The
    // year-less "WONDER WOMAN 1944" resembles "wonder woman" enough to pass on its own.
    private val wonderWoman = listOf(
        movie("Wonder Woman", "2017", "297762"),
        movie("Wonder Woman", "1974", "161620"),
        MediaSearchResult.Movie(title = "WONDER WOMAN 1944", year = null, tmdbId = "1782318", provider = "TMDb", matchConfidence = 73.3),
    )
    private val wonderWoman1984 = listOf(movie("Wonder Woman 1984", "2020", "464052"))

    @Test
    fun `a year that belongs to the title is matched as part of it`() = runTest {
        val provider = FakeTmdb(wonderWoman, resultsByTitle = mapOf("wonder woman 1984" to wonderWoman1984))

        val run = runProcessor(provider, "Wonder.Woman.1984.1080p.mkv")

        assertEquals("464052", run.movieOf("Wonder.Woman.1984.1080p.mkv"))
        assertEquals(listOf("wonder woman", "wonder woman 1984"), run.provider.searchedTitles)
    }

    @Test
    fun `the title is not searched with the year when a film from that year matches`() = runTest {
        val provider = FakeTmdb(listOf(movie("Alien", "1979", "348")), resultsByTitle = mapOf("alien 1979" to listOf(movie("Alien 1979", "2020", "1"))))

        val run = runProcessor(provider, "Alien.1979.1080p.mkv")

        assertEquals("348", run.movieOf("Alien.1979.1080p.mkv"))
        assertEquals(listOf("alien"), run.provider.searchedTitles)
    }

    @Test
    fun `the title reading does not replace a better match`() = runTest {
        // Pinocchio (2013) is a year off but matches far better than an unrelated "Pinocchio 2012".
        val provider = FakeTmdb(
            results = listOf(movie("Pinocchio", "2013", "246790")),
            resultsByTitle = mapOf("pinocchio 2012" to listOf(movie("Pinocchio 2012 Behind the Scenes", "2013", "2").copy(matchConfidence = 50.0))),
        )

        val run = runProcessor(provider, "Pinocchio.2012.1080p.BluRay.mkv")

        assertEquals("246790", run.movieOf("Pinocchio.2012.1080p.BluRay.mkv"))
    }

    @Test
    fun `a year set by the user is never read as part of the title`() = runTest {
        val provider = FakeTmdb(wonderWoman, resultsByTitle = mapOf("wonder woman 1984" to wonderWoman1984))

        val run = runProcessor(provider, "Wonder.Woman.1984.1080p.mkv", yearOverride = "1984")

        assertEquals(listOf("wonder woman"), run.provider.searchedTitles)
    }
}
