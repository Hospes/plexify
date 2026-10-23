package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.MetadataTimeoutException
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A run over a library: the ID a path names picks the match, so a file reads back to the media it was filed as. */
class IdLookupTest {

    // Live TMDB (October 2026): searching "hack" ranks CBS's Hack above the anime .hack, both from 2002.
    private val cbsHack = MediaSearchResult.TvShow(title = "Hack", year = "2002", tmdbId = "10457", provider = "TMDb", matchConfidence = 100.0)
    private val dotHack = MediaSearchResult.TvShow(title = ".hack", year = "2002", tmdbId = "8864", provider = "TMDb", matchConfidence = 80.0)
    private val hackShows = listOf(dotHack, cbsHack)
    private val hackSeasons = mapOf("8864" to mapOf(1 to 1..28), "10457" to mapOf(1 to 1..22))

    // TMDB movie 1721501 is titled "?", which plexify names "tmdb-1721501"; no search finds it by that.
    private val questionMark = MediaSearchResult.Movie(title = "?", year = "2026", tmdbId = "1721501", provider = "TMDb")

    private val episode1 = "hack (2002) - S01E01 - Role Play.mkv"
    private val episode2 = "hack (2002) - S01E02 - Guardian.mkv"
    private val jellyfinShow = "hack (2002) [tmdbid-8864]/Season 01"
    private fun jellyfin(name: String) = "$jellyfinShow/$name"

    @Test
    fun `an episode is matched by the TMDB ID in its show folder`() = runTest {
        val run = runProcessor(FakeTmdb(hackShows, seasons = hackSeasons), jellyfin(episode1), jellyfin(episode2))

        assertEquals("8864", run.showOf(episode1))
        assertEquals("8864", run.showOf(episode2))
        // One lookup and one season fetch for the show, no search.
        assertEquals(listOf("tmdb-8864"), run.provider.idLookups)
        assertEquals(listOf("8864:1"), run.provider.seasonFetches)
        assertTrue(run.provider.searchedTitles.isEmpty())
    }

    @Test
    fun `an episode is matched by the IMDb ID in a Plex show folder`() = runTest {
        val run = runProcessor(FakeTmdb(hackShows, seasons = hackSeasons), "hack (2002) {imdb-tt8864}/Season 01/$episode1", namingStrategy = NamingStrategy.Plex)

        val matched = run.episodeOf(episode1)!!.show
        assertEquals(CanonicalMedia.TvShow(".hack", 2002, imdbId = "tt8864", tmdbId = "8864"), matched)
        // The IMDb ID came with the lookup.
        assertTrue(run.provider.externalIdLookups.isEmpty())
    }

    @Test
    fun `an episode found by ID still goes through the episode groups`() = runTest {
        // TMDB keeps .hack as one season, which the release splits in two; CBS's Hack has a season 2.
        val show = CanonicalMedia.TvShow(".hack", 2002, tmdbId = "8864")
        val cours = CanonicalMedia.EpisodeGroup(
            name = "Seasons", type = CanonicalMedia.EpisodeGroup.Type.TV,
            parts = listOf(
                CanonicalMedia.EpisodeGroup.Part("Season 1", 1, (1..12).map { CanonicalMedia.Episode(show, 1, it, "E$it") }),
                CanonicalMedia.EpisodeGroup.Part("Season 2", 2, (13..26).map { CanonicalMedia.Episode(show, 1, it, "E$it") }),
            ),
        )
        val seasons = hackSeasons + ("10457" to mapOf(1 to 1..22, 2 to 1..18))
        val provider = FakeTmdb(hackShows, seasons = seasons, episodeGroupsOf = { if (it.tmdbId == "8864") listOf(cours) else emptyList() })

        val run = runProcessor(provider, "hack (2002) [tmdbid-8864]/Season 02/hack (2002) - S02E01 - Unison.mkv")

        val matched = run.episodeOf("hack (2002) - S02E01 - Unison.mkv")!!
        assertEquals("8864", matched.show.tmdbId)
        assertEquals(1 to 13, matched.season to matched.episode)
    }

    @Test
    fun `a show found by ID has no runner-up for a season it lacks`() = runTest {
        val run = runProcessor(FakeTmdb(hackShows, seasons = hackSeasons), "hack (2002) [tmdbid-8864]/Season 03/hack (2002) - S03E01 - X.mkv")

        assertTrue(run.organizer.organized.isEmpty())
        assertEquals(1, run.stats.skipped)
        assertEquals(listOf("8864:3"), run.provider.seasonFetches)
    }

    @Test
    fun `a movie named by its ID reads back to that movie`() = runTest {
        val name = "tmdb-1721501 (2026) - [1080p] [WEB-DL].mkv"

        val run = runProcessor(FakeTmdb(emptyList(), idRecords = listOf(questionMark)), "tmdb-1721501 (2026)/$name", namingStrategy = NamingStrategy.Plex)

        assertEquals("1721501", run.movieOf(name))
        assertTrue(run.provider.searchedTitles.isEmpty())
    }

    @Test
    fun `a Jellyfin movie named by its ID reads back by its tag`() = runTest {
        val name = "tmdb-1721501 (2026) [tmdbid-1721501] - [1080p] [WEB-DL].mkv"

        val run = runProcessor(FakeTmdb(emptyList(), idRecords = listOf(questionMark)), "tmdb-1721501 (2026) [tmdbid-1721501]/$name")

        assertEquals("1721501", run.movieOf(name))
    }

    @Test
    fun `a title that only looks like a plexify ID name is searched`() = runTest {
        // Movie 123 has a usable title, so plexify would never have named it "tmdb-123".
        val real = MediaSearchResult.Movie(title = "tmdb-123", year = "2020", tmdbId = "555", provider = "TMDb", matchConfidence = 100.0)
        val other = MediaSearchResult.Movie(title = "The Lord of the Rings", year = "2020", tmdbId = "123", provider = "TMDb")

        val run = runProcessor(FakeTmdb(listOf(real), idRecords = listOf(other)), "tmdb-123 (2020).mkv")

        assertEquals("555", run.movieOf("tmdb-123 (2020).mkv"))
        assertEquals(listOf("tmdb 123"), run.provider.searchedTitles)
    }

    @Test
    fun `an ID TMDB no longer has falls back to the title search`() = runTest {
        val run = runProcessor(FakeTmdb(hackShows, seasons = hackSeasons), "hack (2002) [tmdbid-99999]/Season 01/$episode1")

        assertEquals(listOf("tmdb-99999"), run.provider.idLookups)
        assertEquals(listOf("hack"), run.provider.searchedTitles)
        assertEquals("10457", run.showOf(episode1))
    }

    @Test
    fun `an ID whose year contradicts the path's falls back to the title search`() = runTest {
        val run = runProcessor(FakeTmdb(hackShows, seasons = hackSeasons), "hack (1990) [tmdbid-8864]/Season 01/hack (1990) - S01E01 - Pilot.mkv")

        assertEquals(listOf("hack"), run.provider.searchedTitles)
        assertTrue(run.organizer.organized.isEmpty())
    }

    @Test
    fun `a title override searches the title instead of the ID`() = runTest {
        val run = runProcessor(FakeTmdb(hackShows, seasons = hackSeasons), jellyfin(episode1), titleOverride = "hack")

        assertTrue(run.provider.idLookups.isEmpty())
        assertEquals("10457", run.showOf(episode1))
    }

    @Test
    fun `a year override searches the title instead of the ID`() = runTest {
        val run = runProcessor(FakeTmdb(hackShows, seasons = hackSeasons), jellyfin(episode1), yearOverride = "2002")

        assertTrue(run.provider.idLookups.isEmpty())
        assertEquals("10457", run.showOf(episode1))
    }

    @Test
    fun `a failed ID lookup fails the file and is tried again for the next one`() = runTest {
        val provider = FakeTmdb(hackShows, seasons = hackSeasons, idLookupFailure = MetadataTimeoutException("TMDB request timed out looking up show tmdb-8864"))

        val run = runProcessor(provider, jellyfin(episode1), jellyfin(episode2))

        assertTrue(run.organizer.organized.isEmpty())
        assertEquals(2, run.stats.failed)
        assertEquals(listOf("tmdb-8864", "tmdb-8864"), run.provider.idLookups)
        assertTrue(run.provider.searchedTitles.isEmpty())
    }

    @Test
    fun `shows whose names clean alike keep their own seasons`() = runTest {
        // Both library folders read "hack (2002)": the first file's show must not place the other's season 1.
        val cbsEpisode = "Hack (2002) - S01E01 - Pilot.mkv"

        val run = runProcessor(FakeTmdb(hackShows, seasons = hackSeasons), jellyfin(episode1), "Hack (2002) [tmdbid-10457]/Season 01/$cbsEpisode")

        assertEquals("8864", run.showOf(episode1))
        assertEquals("10457", run.showOf(cbsEpisode))
    }

    @Test
    fun `a tag naming another title gives way to the title search`() = runTest {
        val breakingBad = MediaSearchResult.TvShow(title = "Breaking Bad", year = "2008", tmdbId = "1396", provider = "TMDb", matchConfidence = 100.0)
        val gameOfThrones = MediaSearchResult.TvShow(title = "Game of Thrones", year = "2011", tmdbId = "1399", provider = "TMDb")
        val name = "Breaking.Bad.S01E01.720p.HDTV [tmdbid-1399].mkv"

        val run = runProcessor(FakeTmdb(listOf(breakingBad), seasons = mapOf("1396" to mapOf(1 to 1..7)), idRecords = listOf(gameOfThrones)), name)

        assertEquals(listOf("tmdb-1399"), run.provider.idLookups)
        assertEquals("1396", run.showOf(name))
    }

    @Test
    fun `an arc release found by ID takes the season its title names`() = runTest {
        // TMDB tags the arc's own title with its season; no search finds the show here, only the ID.
        val demonSlayer = MediaSearchResult.TvShow(
            title = "Demon Slayer: Kimetsu no Yaiba", year = "2019", tmdbId = "85937", provider = "TMDb",
            alternativeTitles = listOf("Kimetsu no Yaiba", "Kimetsu no Yaiba: Yuukaku-hen"),
            seasonTitles = mapOf("Kimetsu no Yaiba: Yuukaku-hen" to 3),
        )
        val name = "[Group] Kimetsu no Yaiba Yuukaku-hen - 01 [1080p].mkv"
        val provider = FakeTmdb(emptyList(), seasons = mapOf("85937" to mapOf(1 to 1..26, 3 to 1..11)), idRecords = listOf(demonSlayer))

        val run = runProcessor(provider, "Kimetsu no Yaiba Yuukaku-hen [tmdbid-85937]/$name")

        val matched = run.episodeOf(name)!!
        assertEquals("85937", matched.show.tmdbId)
        assertEquals(3 to 1, matched.season to matched.episode)
    }
}
