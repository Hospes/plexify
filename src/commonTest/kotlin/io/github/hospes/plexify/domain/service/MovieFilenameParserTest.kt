package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import kotlin.test.Test
import kotlin.test.assertTrue

class MovieFilenameParserTest {

    private data class TestCase(val filename: String, val expected: ParsedMediaInfo.Movie)

    private val movies = listOf(
        // --- Original Test Cases ---
        TestCase(
            filename = "The.Matrix.1999.1080p.BluRay.x264-YTS.mkv",
            expected = ParsedMediaInfo.Movie(title = "the matrix", year = "1999", resolution = "1080p", quality = "BluRay")
        ),
        TestCase(
            filename = "Blade.Runner.2049.[2017].UHD.BluRay.2160p.x265-TERMiNAL.mkv",
            expected = ParsedMediaInfo.Movie(title = "blade runner 2049", year = "2017", resolution = "2160p", quality = "BluRay")
        ),
        TestCase(
            filename = "2001.A.Space.Odyssey.1968.720p.BRRip.x264.mp4",
            expected = ParsedMediaInfo.Movie(title = "2001 a space odyssey", year = "1968", resolution = "720p", quality = "BRRip")
        ),

        // --- New Cases from Real Filenames ---

        // Test case: No year present
        TestCase(
            filename = "Avengers.Endgame.BDRip.1080p.pk.mkv",
            expected = ParsedMediaInfo.Movie(title = "avengers endgame", year = null, resolution = "1080p", quality = "BDRip")
        ),
        // Test case: Number in title
        TestCase(
            filename = "Deadpool.2.2018.1080p.BluRay.3xRus.Ukr.Eng.NTb.mkv",
            expected = ParsedMediaInfo.Movie(title = "deadpool 2", year = "2018", resolution = "1080p", quality = "BluRay")
        ),
        // Test case: Year in parentheses
        TestCase(
            filename = "Ghostbusters. Frozen Empire (2024).1080p.mkv",
            expected = ParsedMediaInfo.Movie(title = "ghostbusters frozen empire", year = "2024", resolution = "1080p")
        ),
        // Test case: Underscore delimiters
        TestCase(
            filename = "Ghostbusters_Afterlife_2021_BDRip_1080p_by_Dalemake.mkv",
            expected = ParsedMediaInfo.Movie(title = "ghostbusters afterlife", year = "2021", resolution = "1080p", quality = "BDRip")
        ),
        // Test case: Year in parentheses followed by bracketed metadata
        TestCase(
            filename = "King Arthur (2004) - [1080p] [Extended] [Director's Cut].mkv",
            expected = ParsedMediaInfo.Movie(title = "king arthur", year = "2004", resolution = "1080p", edition = "Extended")
        ),
        // Test case: Hyphen in title
        TestCase(
            filename = "Mission Impossible - Dead Reckoning Part One (2023).mkv",
            expected = ParsedMediaInfo.Movie(title = "mission impossible dead reckoning part one", year = "2023")
        ),
        // Test case: Minimalist filename with only Title and Year
        TestCase(
            filename = "The.Naked.Gun.1988.mkv",
            expected = ParsedMediaInfo.Movie(title = "the naked gun", year = "1988")
        ),
        // Test case: No year, but contains a release group
        TestCase(
            filename = "Superman.1080p.rus.LostFilm.TV.mkv",
            expected = ParsedMediaInfo.Movie(title = "superman", year = null, resolution = "1080p", releaseGroup = "LostFilm")
        ),
        // Test case: Complex title with a colon, year in parentheses, SDR label
        TestCase(
            filename = "The Lord of the Rings. The War of the Rohirrim (2024) WEB-DL.2160p.SDR.mkv",
            expected = ParsedMediaInfo.Movie(
                title = "the lord of the rings the war of the rohirrim",
                year = "2024",
                resolution = "2160p",
                quality = "WEB-DL",
                hdr = "SDR",
            )
        ),
        // Test case: Edition tag 'Extended'
        TestCase(
            filename = "Who.Am.I.1998.Extended.WEB-DL.1080p.mkv",
            expected = ParsedMediaInfo.Movie(title = "who am i", year = "1998", resolution = "1080p", quality = "WEB-DL", edition = "Extended")
        ),
        // Test case: Hyphenated title (X-Men)
        TestCase(
            filename = "X-MEN.Dark.Phoenix.2019.BDRip.1080p.mkv",
            expected = ParsedMediaInfo.Movie(title = "x men dark phoenix", year = "2019", resolution = "1080p", quality = "BDRip")
        ),
        // Test case: Transliterated title with underscores
        TestCase(
            filename = "Trener_Karter_2005_BDRip_by_Dalemake.avi",
            expected = ParsedMediaInfo.Movie(title = "trener karter", year = "2005", quality = "BDRip")
        ),
        // Test case: New quality tag 'WEB-DLRip'
        TestCase(
            filename = "Ryzhaya.Sonya.2025.WEB-DLRip.AVC.mkv",
            expected = ParsedMediaInfo.Movie(title = "ryzhaya sonya", year = "2025", quality = "WEB-DLRip")
        ),
        // Test case: Robustness against unicode garbage characters, SDR label
        TestCase(
            filename = "The.Ritual.2025.2160p.UHD.AMZN.WEB-DL.SDR.HEVC-□'$'\\235''е□'$'\\207''ипо□'$'\\200''□'$'\\203''к.mkv",
            expected = ParsedMediaInfo.Movie(title = "the ritual", year = "2025", resolution = "2160p", quality = "WEB-DL", hdr = "SDR")
        ),

        // --- HDR test cases ---
        TestCase(
            filename = "Dune.Part.Two.2024.2160p.UHD.BluRay.HDR10.x265-GROUP.mkv",
            expected = ParsedMediaInfo.Movie(title = "dune part two", year = "2024", resolution = "2160p", quality = "BluRay", hdr = "HDR10")
        ),
        TestCase(
            filename = "Avatar.The.Way.of.Water.2022.2160p.HDR10+.WEB-DL.mkv",
            expected = ParsedMediaInfo.Movie(title = "avatar the way of water", year = "2022", resolution = "2160p", quality = "WEB-DL", hdr = "HDR10+")
        ),
        TestCase(
            filename = "Interstellar.2014.2160p.UHD.BluRay.DV.mkv",
            expected = ParsedMediaInfo.Movie(title = "interstellar", year = "2014", resolution = "2160p", quality = "BluRay", hdr = "DV")
        ),

        // --- Edition test cases ---
        TestCase(
            filename = "Blade.Runner.1982.Directors.Cut.1080p.BluRay.mkv",
            expected = ParsedMediaInfo.Movie(title = "blade runner", year = "1982", resolution = "1080p", quality = "BluRay", edition = "Directors Cut")
        ),
        TestCase(
            filename = "The.Dark.Knight.2008.IMAX.1080p.BluRay.mkv",
            expected = ParsedMediaInfo.Movie(title = "the dark knight", year = "2008", resolution = "1080p", quality = "BluRay", edition = "IMAX")
        ),
        TestCase(
            filename = "Aliens.1986.Remastered.1080p.BluRay.mkv",
            expected = ParsedMediaInfo.Movie(title = "aliens", year = "1986", resolution = "1080p", quality = "BluRay", edition = "Remastered")
        ),

        // --- Names plexify itself writes (re-running over an organized library) ---
        // Jellyfin template: "{CleanTitle} ({year}) [tmdbid-{tmdbid}]{version}.{ext}"
        TestCase(
            filename = "Inception (2010) [tmdbid-27205] - [1080p] [BluRay].mkv",
            expected = ParsedMediaInfo.Movie(title = "inception", year = "2010", resolution = "1080p", quality = "BluRay")
        ),
        TestCase(
            filename = "Inception (2010) [tmdbid-27205].mkv",
            expected = ParsedMediaInfo.Movie(title = "inception", year = "2010")
        ),
        TestCase(
            filename = "Blade Runner 2049 (2017) [tmdbid-335984] - [2160p] [BluRay] [HDR10].mkv",
            expected = ParsedMediaInfo.Movie(title = "blade runner 2049", year = "2017", resolution = "2160p", quality = "BluRay", hdr = "HDR10")
        ),
        // Plex template: "{CleanTitle} ({year}){version}.{ext}"
        TestCase(
            filename = "Inception (2010) - [1080p] [BluRay].mkv",
            expected = ParsedMediaInfo.Movie(title = "inception", year = "2010", resolution = "1080p", quality = "BluRay")
        ),
        // A version suffix holding only an edition has no stop word to cut the title at
        TestCase(
            filename = "Blade Runner (1982) - [Final Cut].mkv",
            expected = ParsedMediaInfo.Movie(title = "blade runner", year = "1982", edition = "Final Cut")
        ),
        // Other ID tag forms Plex and Jellyfin read
        TestCase(
            filename = "Inception (2010) {tmdb-27205}.mkv",
            expected = ParsedMediaInfo.Movie(title = "inception", year = "2010")
        ),
        TestCase(
            filename = "Inception (2010) {imdb-tt1375666} - [2160p] [DV].mkv",
            expected = ParsedMediaInfo.Movie(title = "inception", year = "2010", resolution = "2160p", hdr = "DV")
        ),
        TestCase(
            filename = "Inception (2010) [imdbid=tt1375666].mkv",
            expected = ParsedMediaInfo.Movie(title = "inception", year = "2010")
        ),

        // --- Years that are part of the title ---
        // A year no release can have yet is a title word
        TestCase(
            filename = "Blade.Runner.2049.1080p.mkv",
            expected = ParsedMediaInfo.Movie(title = "blade runner 2049", year = null, resolution = "1080p")
        ),
        // A year with no title before it is the title
        TestCase(
            filename = "1917.1080p.BluRay.mkv",
            expected = ParsedMediaInfo.Movie(title = "1917", year = null, resolution = "1080p", quality = "BluRay")
        ),
        TestCase(
            filename = "1917.2019.1080p.mkv",
            expected = ParsedMediaInfo.Movie(title = "1917", year = "2019", resolution = "1080p")
        ),
        TestCase(
            filename = "2001.A.Space.Odyssey.1080p.mkv",
            expected = ParsedMediaInfo.Movie(title = "2001 a space odyssey", year = null, resolution = "1080p")
        ),
        TestCase(
            filename = "2046.2004.720p.mkv",
            expected = ParsedMediaInfo.Movie(title = "2046", year = "2004", resolution = "720p")
        ),
        // Either reading is possible; matching tries "wonder woman 1984" when this one has no match from 1984
        TestCase(
            filename = "Wonder.Woman.1984.1080p.mkv",
            expected = ParsedMediaInfo.Movie(title = "wonder woman", year = "1984", resolution = "1080p")
        ),

        // --- Not episodes, despite the episode patterns ---
        // " - 1979" is a year, not a fansub episode number
        TestCase(
            filename = "Alien - 1979.mkv",
            expected = ParsedMediaInfo.Movie(title = "alien", year = "1979")
        ),
        // A number followed by a bracketed year is part of the title
        TestCase(
            filename = "Rocky - 2 (1979).mkv",
            expected = ParsedMediaInfo.Movie(title = "rocky 2", year = "1979")
        ),
        // Frame size is not 1x01 numbering
        TestCase(
            filename = "Some.Movie.2019.1920x1080.x264.mkv",
            expected = ParsedMediaInfo.Movie(title = "some movie", year = "2019")
        ),
        // A bracketed title is not a release group
        TestCase(
            filename = "[REC] (2007).mkv",
            expected = ParsedMediaInfo.Movie(title = "rec", year = "2007")
        ),
    )

    @Test
    fun `parses list of movies correctly`() {
        // Every failing name at once, not only the first
        val failures = movies.mapNotNull { (filename, expected) ->
            val actual = MediaFilenameParser.parse(filename)
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
