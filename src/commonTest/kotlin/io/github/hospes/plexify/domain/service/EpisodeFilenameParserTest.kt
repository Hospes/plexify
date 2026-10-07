package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpisodeFilenameParserTest {

    private data class TestCase(
        val filename: String,
        val parentDirName: String? = null,
        val expected: ParsedMediaInfo.Episode,
    )

    private val episodes = listOf(
        // --- Tier 1: Standard SxxExx ---
        TestCase(
            filename = "Breaking.Bad.S01E01.Pilot.1080p.BluRay.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "breaking bad", season = 1, episode = 1, year = null, resolution = "1080p", quality = "BluRay")
        ),
        TestCase(
            filename = "Game.of.Thrones.S08E06.720p.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "game of thrones", season = 8, episode = 6, year = null, resolution = "720p")
        ),
        TestCase(
            filename = "The.Boys.S03E01.LostFilm.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "the boys", season = 3, episode = 1, year = null, releaseGroup = "LostFilm")
        ),

        // --- Tier 2: "Season N" keyword + [NN] bracket ---
        TestCase(
            filename = "Tsue_to_Tsurugi_no_Wistoria_Season_2_[01]_[HEVC].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "tsue to tsurugi no wistoria", season = 2, episode = 1, year = null)
        ),
        TestCase(
            filename = "Some.Anime.Season.1.[12].1080p.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "some anime", season = 1, episode = 12, year = null, resolution = "1080p")
        ),
        TestCase(
            filename = "Some.Anime.S2.[05].1080p.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "some anime", season = 2, episode = 5, year = null, resolution = "1080p")
        ),

        // --- Tier 3: [NN] bracket + season from parent directory ---
        TestCase(
            filename = "Dungeon.Meshi.[13].[1080p].mkv",
            parentDirName = "Season 2",
            expected = ParsedMediaInfo.Episode(showTitle = "dungeon meshi", season = 2, episode = 13, year = null, resolution = "1080p")
        ),
        TestCase(
            filename = "[01].mkv",
            parentDirName = "S03",
            expected = ParsedMediaInfo.Episode(showTitle = "", season = 3, episode = 1, year = null)
        ),

        // --- Tier 4: [NN] bracket only (absolute numbering, no season) ---
        TestCase(
            filename = "Dungeon.Meshi.[13].[720p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "dungeon meshi", season = null, episode = 13, year = null, resolution = "720p")
        ),
        TestCase(
            filename = "Naruto.Shippuden.[420].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "naruto shippuden", season = null, episode = 420, year = null)
        ),
    )

    // Split-cour anime: the release's S2 is TMDB's S1E13+, remapped later via episode groups,
    // so only title, season and episode matter here.
    @Test
    fun `parses split cour release numbering`() {
        val firstCour = MediaFilenameParser.parse(
            "Gate_[01]_[AniLibria_Tv]_[HDTV-Rip_720p].mkv",
            "Gate - AniLibria.TV [HDTV-Rip 720p]",
        ) as ParsedMediaInfo.Episode
        assertEquals("gate", firstCour.showTitle)
        assertEquals(null, firstCour.season)
        assertEquals(1, firstCour.episode)

        val secondCour = MediaFilenameParser.parse(
            "Gate_S2_[12]_[AniLibria_TV]_[HDTV-Rip_720p].mkv",
            "Gate S2 - AniLibria.TV [HDTV-Rip 720p]",
        ) as ParsedMediaInfo.Episode
        assertEquals("gate", secondCour.showTitle)
        assertEquals(2, secondCour.season)
        assertEquals(12, secondCour.episode)
    }

    @Test
    fun `parses list of episodes correctly`() {
        episodes.forEach { (filename, parentDirName, expected) ->
            val actual = MediaFilenameParser.parse(filename, parentDirName)

            assertTrue(
                actual = (actual == expected),
                message = """
            Test failed for filename: '$filename'
            -------------------------------------------------
            Expected: $expected
            Actual:   $actual
            -------------------------------------------------
            """.trimIndent()
            )
        }
    }
}
