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
        // A year in the show's own title is not the episode's year: "1923" premiered in 2022
        TestCase(
            filename = "1923.S01E01.1080p.WEB.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "1923", season = 1, episode = 1, year = null, resolution = "1080p")
        ),
        TestCase(
            filename = "1923.S01E01.2022.1080p.WEB.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "1923", season = 1, episode = 1, year = "2022", resolution = "1080p")
        ),
        TestCase(
            filename = "Blade.Runner.2099.S01E01.2049.1080p.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "blade runner 2099", season = 1, episode = 1, year = null, resolution = "1080p")
        ),
        TestCase(
            filename = "Space.1999.S01E01.Breakaway.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "space", season = 1, episode = 1, year = "1999", bareShowYear = true)
        ),
        TestCase(
            filename = "[Group] 1923 - 01 [1080p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "1923", season = null, episode = 1, year = null, resolution = "1080p", releaseGroup = "Group")
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
        // A multi-episode file plexify wrote
        TestCase(
            filename = "Breaking Bad (2008) - S01E01-E02 - Pilot & Cat's in the Bag - [720p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "breaking bad", season = 1, episode = 1, lastEpisode = 2, year = "2008", resolution = "720p")
        ),

        // Sonarr's names: the same shape, with the quality after the episode title instead of a {version} suffix
        TestCase(
            filename = "The Show (2010) - S01E01 - Pilot WEBDL-1080p.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "the show", season = 1, episode = 1, year = "2010", resolution = "1080p", quality = "WEBDL")
        ),
        TestCase(
            filename = "The Show (2010) - S01E01 - Pilot [Bluray-2160p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "the show", season = 1, episode = 1, year = "2010", resolution = "2160p", quality = "Bluray")
        ),
        // Without a {version} suffix, an edition word is still the episode title's
        TestCase(
            filename = "Some Show (2020) - S02E05 - The Final Cut.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "some show", season = 2, episode = 5, year = "2020")
        ),

        // --- A bare year closing a scene show name is the show's year ---
        TestCase(
            filename = "Doctor.Who.2005.S01E01.Rose.1080p.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "doctor who", season = 1, episode = 1, year = "2005", resolution = "1080p", bareShowYear = true)
        ),
        TestCase(
            filename = "The.Flash.2014.S01E01.720p.HDTV.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "the flash", season = 1, episode = 1, year = "2014", resolution = "720p", quality = "HDTV", bareShowYear = true)
        ),

        // --- SxxEyy variants ---
        TestCase(
            filename = "Show.S01E01[1080p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "show", season = 1, episode = 1, year = null, resolution = "1080p")
        ),
        TestCase(
            filename = "Show S01E05v2 [1080p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "show", season = 1, episode = 5, year = null, resolution = "1080p")
        ),
        // No show name: the show comes from the folder
        TestCase(
            filename = "S01E01 - Pilot.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "", season = 1, episode = 1, year = null)
        ),
        TestCase(
            filename = "Show.S01.E01.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "show", season = 1, episode = 1, year = null)
        ),
        TestCase(
            filename = "Show S01 E02 720p.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "show", season = 1, episode = 2, year = null, resolution = "720p")
        ),
        TestCase(
            filename = "One.Piece.S01E1071.1080p.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "one piece", season = 1, episode = 1071, year = null, resolution = "1080p")
        ),
        // The name plexify writes for an episode past 999
        TestCase(
            filename = "One Piece (1999) - S01E1071 - Luffy's Fury - [1080p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "one piece", season = 1, episode = 1071, year = "1999", resolution = "1080p")
        ),
        TestCase(
            filename = "Friends.1x01[720p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "friends", season = 1, episode = 1, year = null, resolution = "720p")
        ),

        // --- Episode numbers past 99, year-numbered seasons ---
        TestCase(
            filename = "One.Piece.S01E100.1080p.WEB-DL.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "one piece", season = 1, episode = 100, year = null, resolution = "1080p", quality = "WEB-DL")
        ),
        TestCase(
            filename = "The.Daily.Show.S2024E01.720p.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "the daily show", season = 2024, episode = 1, year = null, resolution = "720p")
        ),

        // --- Multi-episode files ---
        TestCase(
            filename = "Breaking.Bad.S01E01E02.720p.BluRay.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "breaking bad", season = 1, episode = 1, lastEpisode = 2, year = null, resolution = "720p", quality = "BluRay")
        ),
        TestCase(
            filename = "Breaking.Bad.S01E01-E02.720p.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "breaking bad", season = 1, episode = 1, lastEpisode = 2, year = null, resolution = "720p")
        ),
        TestCase(
            filename = "Breaking.Bad.S01E01-02.720p.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "breaking bad", season = 1, episode = 1, lastEpisode = 2, year = null, resolution = "720p")
        ),
        TestCase(
            filename = "Doctor.Who.S04E12E13E14.1080p.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "doctor who", season = 4, episode = 12, lastEpisode = 14, year = null, resolution = "1080p")
        ),
        // A resolution right after the dash is not a range end
        TestCase(
            filename = "Breaking.Bad.S01E05-720p.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "breaking bad", season = 1, episode = 5, year = null, resolution = "720p")
        ),
        // A "range" that doesn't go forward is just the first episode
        TestCase(
            filename = "Breaking.Bad.S01E05-03.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "breaking bad", season = 1, episode = 5, year = null)
        ),

        // --- 1x01 numbering ---
        TestCase(
            filename = "Friends.1x01.The.One.Where.Monica.Gets.a.Roommate.720p.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "friends", season = 1, episode = 1, year = null, resolution = "720p")
        ),
        TestCase(
            filename = "Friends - 02x24 - The One with Barry and Mindy's Wedding.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "friends", season = 2, episode = 24, year = null)
        ),
        TestCase(
            filename = "Friends.10x17-18.The.Last.One.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "friends", season = 10, episode = 17, lastEpisode = 18, year = null)
        ),
        TestCase(
            filename = "Friends.10x17-10x18.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "friends", season = 10, episode = 17, lastEpisode = 18, year = null)
        ),

        // --- Anime fansub style: "[Group] Show - 01 (1080p) [CRC32]" ---
        TestCase(
            filename = "[SubsPlease] Sousou no Frieren - 01 (1080p) [ABCD1234].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "sousou no frieren", season = null, episode = 1, year = null, resolution = "1080p", releaseGroup = "SubsPlease")
        ),
        TestCase(
            filename = "[Erai-raws] Kaiju No. 8 - 12 [1080p][Multiple Subtitle].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "kaiju no 8", season = null, episode = 12, year = null, resolution = "1080p", releaseGroup = "Erai-raws")
        ),
        TestCase(
            filename = "Sousou no Frieren - 05v2.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "sousou no frieren", season = null, episode = 5, year = null)
        ),
        // Absolute numbering past a season's length
        TestCase(
            filename = "[SubsPlease] One Piece - 1071 (1080p) [8D4B2C9A].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "one piece", season = null, episode = 1071, year = null, resolution = "1080p", releaseGroup = "SubsPlease")
        ),
        TestCase(
            filename = "[SubsPlease] Dungeon Meshi - 13 (1080p) [ABCD1234].mkv",
            parentDirName = "Season 2",
            expected = ParsedMediaInfo.Episode(showTitle = "dungeon meshi", season = 2, episode = 13, year = null, resolution = "1080p", releaseGroup = "SubsPlease")
        ),
        // Season marker closing the title
        TestCase(
            filename = "[SubsPlease] Mushoku Tensei S2 - 01 (1080p) [ABCD1234].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "mushoku tensei", season = 2, episode = 1, year = null, resolution = "1080p", releaseGroup = "SubsPlease")
        ),
        TestCase(
            filename = "[Group] Spy x Family Season 2 - 03 [720p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "spy x family", season = 2, episode = 3, year = null, resolution = "720p", releaseGroup = "Group")
        ),
        TestCase(
            filename = "[Group] Vinland Saga 2nd Season - 07 [1080p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "vinland saga", season = 2, episode = 7, year = null, resolution = "1080p", releaseGroup = "Group")
        ),
        TestCase(
            filename = "[SubsPlease] Ranma 1-2 (2024) - 03 (1080p) [ABCD1234].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "ranma 1 2", season = null, episode = 3, year = "2024", resolution = "1080p", releaseGroup = "SubsPlease")
        ),
        TestCase(
            filename = "[Judas]_Mob_Psycho_100_-_04_[1080p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "mob psycho 100", season = null, episode = 4, year = null, resolution = "1080p", releaseGroup = "Judas")
        ),
        // Episode title after the number
        TestCase(
            filename = "Mob Psycho 100 - 04 - Idiots Only Event.mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "mob psycho 100", season = null, episode = 4, year = null)
        ),
        // A leading group also stays out of the title in the other forms
        TestCase(
            filename = "[AniLibria] Gate [05] [720p].mkv",
            expected = ParsedMediaInfo.Episode(showTitle = "gate", season = null, episode = 5, year = null, resolution = "720p", releaseGroup = "AniLibria")
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

    // Filed as episode 12 it would collide with the real one; better left unmatched.
    @Test
    fun `a recap special numbered 12 and a half is not episode 12`() {
        val parsed = MediaFilenameParser.parse("[SubsPlease] Sousou no Frieren - 12.5 (1080p) [ABCD1234].mkv")

        assertTrue(parsed !is ParsedMediaInfo.Episode || parsed.episode != 12, "parsed as $parsed")
    }

    @Test
    fun `parses list of episodes correctly`() {
        // Every failing name at once, not only the first
        val failures = episodes.mapNotNull { (filename, parentDirName, expected) ->
            val actual = MediaFilenameParser.parse(filename, parentDirName)
            if (actual == expected) null else """
            Test failed for filename: '$filename'
            -------------------------------------------------
            Expected: $expected
            Actual:   $actual
            -------------------------------------------------
            """.trimIndent()
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
