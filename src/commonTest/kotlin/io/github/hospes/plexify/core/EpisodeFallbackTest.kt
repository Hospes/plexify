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
        assertEquals(1, run.stats.failed)
        assertEquals(listOf("1:4"), run.provider.seasonFetches)
    }

    @Test
    fun `fails the file when the runner-up's season fails to load`() = runTest {
        val provider = FakeTmdb(
            searchResults,
            seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..8)),
            failingSeasons = setOf("2:4"),
        )

        val run = runProcessor(provider, "Ghosts.S04E01.1080p.WEB.mkv")

        assertTrue(run.organizer.organized.isEmpty())
        assertEquals(1, run.stats.failed)
        assertEquals(0, run.stats.skipped)
    }

    @Test
    fun `skips the file when no candidate has the episode`() = runTest {
        val provider = FakeTmdb(searchResults, seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..8)))

        val run = runProcessor(provider, "Ghosts.S04E12.1080p.WEB.mkv")

        assertTrue(run.organizer.organized.isEmpty())
        assertEquals(1, run.stats.skipped)
    }

    @Test
    fun `falls back to a same-titled show of the same year`() = runTest {
        val results = listOf(
            MediaSearchResult.TvShow(title = "Ghosts", year = "2019", tmdbId = "1", provider = "TMDb", matchConfidence = 100.0),
            MediaSearchResult.TvShow(title = "Ghosts", year = "2019", tmdbId = "2", provider = "TMDb", matchConfidence = 100.0),
        )
        val provider = FakeTmdb(results, seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..8)))

        val run = runProcessor(provider, "Ghosts.S04E01.1080p.WEB.mkv")

        assertEquals("2", run.showOf("Ghosts.S04E01.1080p.WEB.mkv"))
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
    fun `tries at most three runner-up shows`() = runTest {
        // Five same-titled shows and no year in the filenames: they rank by provider confidence, show 1 first.
        // Only the third runner-up has season 4, and only the fourth has season 5.
        val results = (1..5).map { n ->
            MediaSearchResult.TvShow(title = "Ghosts", year = "${2014 + n}", tmdbId = "$n", provider = "TMDb", matchConfidence = 105.0 - 5 * n)
        }
        val provider = FakeTmdb(results, seasons = mapOf("1" to mapOf(1 to 1..6), "4" to mapOf(4 to 1..8), "5" to mapOf(5 to 1..8)))

        val run = runProcessor(provider, "Ghosts.S04E01.1080p.WEB.mkv", "Ghosts.S05E01.1080p.WEB.mkv")

        assertEquals("4", run.showOf("Ghosts.S04E01.1080p.WEB.mkv"))
        assertEquals(null, run.showOf("Ghosts.S05E01.1080p.WEB.mkv"))
        assertEquals(1, run.stats.skipped)
        // MatchScoring.MAX_FALLBACK_SHOWS: show 5, the fourth runner-up, is never asked for season 5.
        assertEquals(listOf("1:4", "2:4", "3:4", "4:4", "1:5", "2:5", "3:5", "4:5"), run.provider.seasonFetches)
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

    // TMDB's Durarara!! (42410): x2 Shou, Ten and Ketsu are one 36-episode season 2, and its "TV cour"
    // group splits them into cours with the x.5 specials slotted in where they belong (as of 2026-10).
    private val durarara = listOf(
        MediaSearchResult.TvShow(title = "Durarara!!", year = "2010", tmdbId = "42410", provider = "TMDb", matchConfidence = 100.0),
    )
    private val durararaCours = { show: CanonicalMedia.TvShow ->
        fun ep(season: Int, episode: Int) = CanonicalMedia.Episode(show, season, episode, "S${season}E$episode")
        listOf(
            CanonicalMedia.EpisodeGroup(
                name = "TV cour",
                type = CanonicalMedia.EpisodeGroup.Type.PRODUCTION,
                parts = listOf(
                    CanonicalMedia.EpisodeGroup.Part("Durarara!!", 1, (1..12).map { ep(1, it) } + ep(0, 1) + (13..24).map { ep(1, it) } + ep(0, 2)),
                    CanonicalMedia.EpisodeGroup.Part("Durarara!!x2 Shou", 2, (1..4).map { ep(2, it) } + ep(0, 3) + (5..12).map { ep(2, it) }),
                    CanonicalMedia.EpisodeGroup.Part("Durarara!!x2 Ten", 3, listOf(ep(2, 13), ep(0, 4)) + (14..24).map { ep(2, it) }),
                    CanonicalMedia.EpisodeGroup.Part("Durarara!!x2 Ketsu", 4, (25..31).map { ep(2, it) } + ep(0, 5) + (32..36).map { ep(2, it) }),
                ),
            )
        )
    }

    @Test
    fun `a special inside a cour does not shift the cour's episodes`() = runTest {
        val provider = FakeTmdb(durarara, seasons = mapOf("42410" to mapOf(1 to 1..24, 2 to 1..36)), episodeGroupsOf = durararaCours)

        val run = runProcessor(provider, "Durarara!! - S03E01.mkv", "Durarara!! - S03E02.mkv", "Durarara!! - S04E08.mkv", "Durarara!! - S04E12.mkv")

        // The release numbers a cour's broadcast episodes; "Romanticist's Chaka-Poko" (S00E04) and
        // "Dufufufu!!" (S00E05) are x.5 specials with no number of their own.
        assertEquals(listOf(2 to 13, 2 to 14, 2 to 32, 2 to 36), listOf("S03E01", "S03E02", "S04E08", "S04E12").map { run.tmdbNumberOf("Durarara!! - $it.mkv") })
    }

    @Test
    fun `specials inside a season the provider has keep counting`() = runTest {
        // TMDB's Texhnolyze (8838) has 20 episodes in season 1 and keeps two DVD episodes as specials; its
        // "Animax Order" counts them, as TheTVDB's 22-episode season 1 does: S01E21 is TMDB's S01E19.
        val texhnolyze = listOf(MediaSearchResult.TvShow(title = "Texhnolyze", year = "2003", tmdbId = "8838", provider = "TMDb", matchConfidence = 100.0))
        val animaxOrder = { show: CanonicalMedia.TvShow ->
            fun ep(season: Int, episode: Int) = CanonicalMedia.Episode(show, season, episode, "S${season}E$episode")
            val part = (1..18).map { ep(1, it) } + ep(0, 2) + ep(0, 3) + (19..20).map { ep(1, it) }
            listOf(CanonicalMedia.EpisodeGroup("Animax Order", CanonicalMedia.EpisodeGroup.Type.PRODUCTION, listOf(CanonicalMedia.EpisodeGroup.Part("Season 1", 1, part))))
        }
        val provider = FakeTmdb(texhnolyze, seasons = mapOf("8838" to mapOf(1 to 1..20)), episodeGroupsOf = animaxOrder)

        val run = runProcessor(provider, "Texhnolyze - S01E21.mkv")

        assertEquals(1 to 19, run.tmdbNumberOf("Texhnolyze - S01E21.mkv"))
    }

    @Test
    fun `each release season of one show keeps its own show`() = runTest {
        val provider = FakeTmdb(searchResults, seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..8)))

        val run = runProcessor(provider, "Ghosts.S01E01.1080p.WEB.mkv", "Ghosts.S04E01.1080p.WEB.mkv")

        // Season 1 is placed in the best match, season 4 in the runner-up: neither placement carries over.
        assertEquals("1", run.showOf("Ghosts.S01E01.1080p.WEB.mkv"))
        assertEquals("2", run.showOf("Ghosts.S04E01.1080p.WEB.mkv"))
    }

    // Provider season 1 split into three release seasons of 8.
    private val thirds = { show: CanonicalMedia.TvShow ->
        val episodes = (1..24).map { CanonicalMedia.Episode(show, 1, it, "Episode $it") }
        listOf(
            CanonicalMedia.EpisodeGroup(
                "Cours", CanonicalMedia.EpisodeGroup.Type.TV, listOf(
                    CanonicalMedia.EpisodeGroup.Part("Cour 1", 1, episodes.take(8)),
                    CanonicalMedia.EpisodeGroup.Part("Cour 2", 2, episodes.drop(8).take(8)),
                    CanonicalMedia.EpisodeGroup.Part("Cour 3", 3, episodes.drop(16)),
                )
            )
        )
    }

    @Test
    fun `an episode group notice is printed once per show and season`() = runTest {
        val provider = FakeTmdb(
            listOf(showResult("Gate", "2015", "62745")),
            seasons = mapOf("62745" to mapOf(1 to 1..24), "42410" to mapOf(1 to 1..24)),
            episodeGroupsOf = thirds,
            resultsByTitle = mapOf("durarara" to listOf(showResult("Durarara", "2010", "42410"))),
        )

        val run = runProcessor(provider, "Gate - S02E01.mkv", "Gate - S02E02.mkv", "Gate - S03E01.mkv", "Durarara - S02E01.mkv")

        assertEquals(4, run.organizer.organized.size)
        assertEquals(
            listOf("Season 2 of 'Durarara'", "Season 2 of 'Gate'", "Season 3 of 'Gate'"),
            run.output.filter { "mapped through episode group" in it }.map { it.trim().substringBefore(" mapped") },
        )
    }

    private fun ProcessorRun<*>.tmdbNumberOf(fileName: String): Pair<Int, Int>? = episodeOf(fileName)?.let { it.season to it.episode }
}
