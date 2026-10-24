package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Plex template tags a folder with the IMDb ID, so a file whose IMDb lookup failed can't be named: filed without
 * the tag, it would start a second folder for the title next to the tagged one a later run files into.
 */
class ExternalIdsFailureTest {

    private val ghosts = listOf(MediaSearchResult.TvShow(title = "Ghosts", year = "2021", tmdbId = "1", provider = "TMDb", matchConfidence = 100.0))
    private val ghostsSeasons = mapOf("1" to mapOf(4 to 1..8))
    private val episode1 = "Ghosts.S04E01.1080p.WEB.mkv"
    private val episode2 = "Ghosts.S04E02.1080p.WEB.mkv"

    @Test
    fun `episodes whose show IMDb lookup fails count as failed and look it up again`() = runTest {
        val provider = FakeTmdb(ghosts, seasons = ghostsSeasons, failingExternalIds = setOf("1"))

        val run = runProcessor(provider, episode1, episode2, namingStrategy = NamingStrategy.Plex)

        assertEquals(2, run.stats.failed)
        assertEquals(0, run.stats.organized)
        // A failed lookup says nothing about the show's IMDb ID, so it is not cached as missing.
        assertEquals(listOf("1", "1"), run.provider.externalIdLookups)
    }

    @Test
    fun `an IMDb ID that loads on a later file is used for it`() = runTest {
        val provider = FakeTmdb(ghosts, seasons = ghostsSeasons, failingExternalIds = setOf("1"), externalIdFailures = 1)

        val run = runProcessor(provider, episode1, episode2, namingStrategy = NamingStrategy.Plex)

        assertEquals(1, run.stats.failed)
        assertEquals(1, run.stats.organized)
        assertNull(run.episodeOf(episode1))
        assertEquals("tt1", run.episodeOf(episode2)?.show?.imdbId)
    }

    @Test
    fun `a show TMDB has no IMDb ID for is filed without one and looked up once`() = runTest {
        val provider = FakeTmdb(ghosts, seasons = ghostsSeasons, withoutImdbId = setOf("1"))

        val run = runProcessor(provider, episode1, episode2, namingStrategy = NamingStrategy.Plex)

        assertEquals(2, run.stats.organized)
        assertNull(run.episodeOf(episode1)?.show?.imdbId)
        assertEquals(listOf("1"), run.provider.externalIdLookups)
    }

    @Test
    fun `versions of a movie whose IMDb lookup fails count as failed`() = runTest {
        val matrix = MediaSearchResult.Movie(title = "The Matrix", year = "1999", tmdbId = "603", provider = "TMDb", matchConfidence = 100.0)
        val provider = FakeTmdb(listOf(matrix), failingExternalIds = setOf("603"))

        val run = runProcessor(provider, "The.Matrix.1999.1080p.BluRay.mkv", "The.Matrix.1999.2160p.BluRay.mkv", namingStrategy = NamingStrategy.Plex)

        assertEquals(2, run.stats.failed)
        assertTrue(run.organizer.organized.isEmpty())
        assertEquals(listOf("603", "603"), run.provider.externalIdLookups)
    }

    @Test
    fun `a show found by the TMDB ID in its path fails the same way`() = runTest {
        val folder = "Ghosts (2021) [tmdbid-1]/Season 04"
        val provider = FakeTmdb(ghosts, seasons = ghostsSeasons, failingExternalIds = setOf("1"))

        val run = runProcessor(provider, "$folder/$episode1", "$folder/$episode2", namingStrategy = NamingStrategy.Plex)

        assertEquals(2, run.stats.failed)
        // Neither the record nor its IMDb ID is cached: the next file looks both up again.
        assertEquals(listOf("tmdb-1", "tmdb-1"), run.provider.idLookups)
        assertEquals(listOf("1", "1"), run.provider.externalIdLookups)
        assertTrue(run.provider.searchedTitles.isEmpty())
    }

    @Test
    fun `a runner-up show whose IMDb lookup fails fails the file`() = runTest {
        // The best match lacks the season, which the runner-up has.
        val shows = listOf(
            MediaSearchResult.TvShow(title = "Ghosts", year = "2019", tmdbId = "1", provider = "TMDb", matchConfidence = 100.0),
            MediaSearchResult.TvShow(title = "Ghosts", year = "2021", tmdbId = "2", provider = "TMDb", matchConfidence = 90.0),
        )
        val provider = FakeTmdb(shows, seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..8)), failingExternalIds = setOf("2"))

        val run = runProcessor(provider, episode1, namingStrategy = NamingStrategy.Plex)

        assertEquals(1, run.stats.failed)
        assertTrue(run.organizer.organized.isEmpty())
    }
}
