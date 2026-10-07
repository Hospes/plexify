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

        // --- Underscore-separated names: '_' is a word char, so metadata must be read from normalized text ---
        TestCase(
            filename = "Gate_[01]_[AniLibria_Tv]_[HDTV-Rip_720p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "gate", season = null, episode = 1, year = null, resolution = "720p", quality = "HDTV")
        ),
        TestCase(
            filename = "Dungeon_Meshi_[13]_[1080p].mkv",
            parentDirName = "Season 2",
            expected = ParsedMediaInfo.Episode(showTitle = "dungeon meshi", season = 2, episode = 13, year = null, resolution = "1080p")
        ),
        TestCase(
            filename = "Tsue_to_Tsurugi_no_Wistoria_Season_2_[01]_[1080p]_[HEVC].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "tsue to tsurugi no wistoria", season = 2, episode = 1, year = null, resolution = "1080p")
        ),
        // AniLibria marks a season's last episode "[NN_END]"; it must parse like its "[NN]" siblings
        TestCase(
            filename = "Kimetsu_no_Yaiba_[25]_[AniLibria.TV]_[HDTVRip_1080p_HEVC].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "kimetsu no yaiba", season = null, episode = 25, year = null, resolution = "1080p")
        ),
        TestCase(
            filename = "Kimetsu_no_Yaiba_[26_END]_[AniLibria.TV]_[HDTVRip_1080p_HEVC].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "kimetsu no yaiba", season = null, episode = 26, year = null, resolution = "1080p")
        ),
        TestCase(
            filename = "Kimetsu_no_Yaiba_-_Yuukaku-hen_[10]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "kimetsu no yaiba yuukaku hen", season = null, episode = 10, year = null, resolution = "1080p", quality = "WEBRip")
        ),
        TestCase(
            filename = "Kimetsu_no_Yaiba_-_Yuukaku-hen_[11_END]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "kimetsu no yaiba yuukaku hen", season = null, episode = 11, year = null, resolution = "1080p", quality = "WEBRip")
        ),
        TestCase(
            filename = "Gate_S2_[24_end]_[AniLibria_TV]_[HDTV-Rip_720p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "gate", season = 2, episode = 24, year = null, resolution = "720p", quality = "HDTV")
        ),
        TestCase(
            filename = "The_Boys_S03E01_2022_720p_WEB-DL_LostFilm.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "the boys", season = 3, episode = 1, year = "2022", resolution = "720p", quality = "WEB-DL", releaseGroup = "LostFilm")
        ),

        // --- Names plexify itself writes: "{CleanTitle} ({year}) - S{season:2}E{episode:2} - {episodeTitle}{version}.{ext}"
        // (same episode template for Plex and Jellyfin) ---
        TestCase(
            filename = "Gate (2015) - S01E13 - The Banquet Begins - [720p] [HDTV].mkv",
            parentDirName = "Season 01",
            expected = ParsedMediaInfo.Episode(showTitle = "gate", season = 1, episode = 13, year = "2015", resolution = "720p", quality = "HDTV")
        ),
        TestCase(
            filename = "Breaking Bad (2008) - S05E16 - Felina.mkv",
            parentDirName = "Season 05",
            expected = ParsedMediaInfo.Episode(showTitle = "breaking bad", season = 5, episode = 16, year = "2008")
        ),
        TestCase(
            filename = "Stranger Things (2016) - S03E08 - Chapter Eight The Battle of Starcourt - [2160p] [WEB-DL] [HDR10].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "stranger things", season = 3, episode = 8, year = "2016", resolution = "2160p", quality = "WEB-DL", hdr = "HDR10")
        ),
        // The episode title is free text: tags inside it must not leak into the metadata
        TestCase(
            filename = "Some Show (2020) - S01E02 - The Extended DVD Cut of 1999 - [1080p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "some show", season = 1, episode = 2, year = "2020", resolution = "1080p")
        ),
        // Episode title containing " - " itself
        TestCase(
            filename = "Some Show (2020) - S01E03 - Part One - The Start - [1080p] [WEB-DL].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "some show", season = 1, episode = 3, year = "2020", resolution = "1080p", quality = "WEB-DL")
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
