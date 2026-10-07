package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpisodeFallbackTest {

    // Two same-titled shows and no year in the filenames: the older one ranks first.
    private val searchResults = listOf(
        MediaSearchResult.TvShow(title = "Ghosts", year = "2019", tmdbId = "1", provider = "TMDb", matchConfidence = 100.0),
        MediaSearchResult.TvShow(title = "Ghosts", year = "2021", tmdbId = "2", provider = "TMDb", matchConfidence = 90.0),
    )

    @Test
    fun `falls back to a runner-up show when the best match lacks the season`() = runTest {
        val provider = FakeTmdb(searchResults, seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..8)))

        val run = runProcessor(provider, "Ghosts.S04E01.1080p.WEB.mkv", "Ghosts.S04E02.1080p.WEB.mkv")

        assertEquals("2", run.showOf("Ghosts.S04E01.1080p.WEB.mkv"))
        assertEquals("2", run.showOf("Ghosts.S04E02.1080p.WEB.mkv"))
        // Each show's season is fetched once; the second file reuses the season's show and cache.
        assertEquals(listOf("1:4", "2:4"), run.provider.seasonFetches)
    }

    @Test
    fun `keeps the best match when it has the season but not the episode`() = runTest {
        val provider = FakeTmdb(searchResults, seasons = mapOf("1" to mapOf(4 to 1..8), "2" to mapOf(4 to 1..10)))

        val run = runProcessor(provider, "Ghosts.S04E10.1080p.WEB.mkv")

        assertTrue(run.organizer.organized.isEmpty())
        assertEquals(1, run.stats.skipped)
        assertEquals(listOf("1:4"), run.provider.seasonFetches)
    }

    @Test
    fun `does not switch shows when the season failed to load`() = runTest {
        val provider = FakeTmdb(
            searchResults,
            seasons = mapOf("1" to mapOf(4 to 1..8), "2" to mapOf(4 to 1..8)),
            failingSeasons = setOf("1:4"),
        )

        val run = runProcessor(provider, "Ghosts.S04E01.1080p.WEB.mkv")

        assertTrue(run.organizer.organized.isEmpty())
        assertEquals(listOf("1:4"), run.provider.seasonFetches)
    }

    @Test
    fun `skips the file when no candidate has the episode`() = runTest {
        val provider = FakeTmdb(searchResults, seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..8)))

        val run = runProcessor(provider, "Ghosts.S04E12.1080p.WEB.mkv")

        assertTrue(run.organizer.organized.isEmpty())
        assertEquals(1, run.stats.skipped)
    }

    @Test
    fun `does not fall back to a show with a merely similar title`() = runTest {
        // "Gates" clears the confidence minimum for "Gate" on similarity and provider confidence,
        // and has the season the release numbers (split-cour anime, no episode group), but it is
        // a different show.
        val results = listOf(
            MediaSearchResult.TvShow(title = "Gate", year = "2015", tmdbId = "1", provider = "TMDb", matchConfidence = 100.0),
            MediaSearchResult.TvShow(title = "Gates", year = "2018", tmdbId = "2", provider = "TMDb", matchConfidence = 80.0),
        )
        val provider = FakeTmdb(results, seasons = mapOf("1" to mapOf(1 to 1..24), "2" to mapOf(1 to 1..10, 2 to 1..10)))

        val run = runProcessor(provider, "Gate_S2_[01]_[1080p].mkv")

        assertTrue(run.organizer.organized.isEmpty())
        assertEquals(1, run.stats.skipped)
        assertEquals(listOf("1:2"), run.provider.seasonFetches)
    }

    @Test
    fun `does not fall back to a same-titled show that premiered after the filename year`() = runTest {
        // Ghosts (2021) scores exactly the confidence minimum against a 2019 filename year, but a
        // show that started two years later cannot hold a 2019 release.
        val results = listOf(
            MediaSearchResult.TvShow(title = "Ghosts", year = "2019", tmdbId = "1", provider = "TMDb", matchConfidence = 100.0),
            MediaSearchResult.TvShow(title = "Ghosts", year = "2021", tmdbId = "2", provider = "TMDb", matchConfidence = 100.0),
        )
        val provider = FakeTmdb(results, seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..8)))

        val run = runProcessor(provider, "Ghosts (2019) - S04E01.mkv")

        assertTrue(run.organizer.organized.isEmpty())
        assertEquals(listOf("1:4"), run.provider.seasonFetches)
    }

    @Test
    fun `one season never splits across runner-up shows`() = runTest {
        val results = searchResults +
                MediaSearchResult.TvShow(title = "Ghosts", year = "2023", tmdbId = "3", provider = "TMDb", matchConfidence = 80.0)
        // Show 2 has only E01-E02 of season 4, show 3 has E01-E10: matched per file, E01 and E05
        // would land in different shows. Whichever file comes first decides the season's show.
        val provider = FakeTmdb(results, seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..2), "3" to mapOf(4 to 1..10)))

        val run = runProcessor(provider, "Ghosts.S04E01.1080p.WEB.mkv", "Ghosts.S04E05.1080p.WEB.mkv")

        val shows = run.organizer.organized.values.map { (it as CanonicalMedia.Episode).show.tmdbId }
        assertTrue(shows.isNotEmpty())
        assertEquals(1, shows.distinct().size, "season 4 split across shows $shows")
    }

    @Test
    fun `a multi-episode file gets the whole range and both titles`() = runTest {
        val provider = FakeTmdb(searchResults, seasons = mapOf("1" to mapOf(1 to 1..6)))

        val run = runProcessor(provider, "Ghosts.S01E01E02.1080p.WEB.mkv")

        val episode = run.organizer.organized["Ghosts.S01E01E02.1080p.WEB.mkv"] as CanonicalMedia.Episode
        assertEquals(1, episode.episode)
        assertEquals(2, episode.lastEpisode)
        assertEquals("Episode 1 & Episode 2", episode.title)
        // The range costs no extra fetch: both episodes come from the cached season.
        assertEquals(listOf("1:1"), run.provider.seasonFetches)
    }

    @Test
    fun `a range running past the season is filed as its first episode`() = runTest {
        val provider = FakeTmdb(searchResults, seasons = mapOf("1" to mapOf(1 to 1..6)))

        val run = runProcessor(provider, "Ghosts.S01E06-E07.1080p.WEB.mkv")

        val episode = run.organizer.organized["Ghosts.S01E06-E07.1080p.WEB.mkv"] as CanonicalMedia.Episode
        assertEquals(6, episode.episode)
        assertEquals(null, episode.lastEpisode)
        assertEquals("Episode 6", episode.title)
    }

    @Test
    fun `a runner-up show picked by the fallback gets its external ids`() = runTest {
        val provider = FakeTmdb(searchResults, seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..8)))

        // The Plex template uses {imdbid}, so matched shows get their IMDb ID looked up.
        val run = runProcessor(provider, "Ghosts.S04E01.1080p.WEB.mkv", "Ghosts.S04E02.1080p.WEB.mkv", namingStrategy = NamingStrategy.Plex)

        val shows = run.organizer.organized.values.map { (it as CanonicalMedia.Episode).show }
        assertEquals(listOf("tt2", "tt2"), shows.map { it.imdbId })
        // Once for the best match when the show is matched, once for the runner-up when it takes the season.
        assertEquals(listOf("1", "2"), run.provider.externalIdLookups)
    }
}
