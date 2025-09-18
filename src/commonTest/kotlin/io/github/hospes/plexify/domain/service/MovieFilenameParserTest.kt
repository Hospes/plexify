package io.github.hospes.plexify.domain.service

import kotlin.test.Test
import kotlin.test.assertTrue

class MovieFilenameParserTest {

    private data class TestCase(val filename: String, val expected: ParsedMovieInfo)

    private val movies = listOf(
        // --- Original Test Cases ---
        TestCase(
            filename = "The.Matrix.1999.1080p.BluRay.x264-YTS.mkv",
            expected = ParsedMovieInfo(title = "the matrix", year = "1999", resolution = "1080p", quality = "BluRay")
        ),
        TestCase(
            filename = "Blade.Runner.2049.[2017].UHD.BluRay.2160p.x265-TERMiNAL.mkv",
            expected = ParsedMovieInfo(title = "blade runner 2049", year = "2017", resolution = "2160p", quality = "BluRay")
        ),
        TestCase(
            filename = "2001.A.Space.Odyssey.1968.720p.BRRip.x264.mp4",
            expected = ParsedMovieInfo(title = "2001 a space odyssey", year = "1968", resolution = "720p", quality = "BRRip")
        ),

        // --- New Cases from Real Filenames ---

        // Test case: No year present
        TestCase(
            filename = "Avengers.Endgame.BDRip.1080p.pk.mkv",
            expected = ParsedMovieInfo(title = "avengers endgame", year = null, resolution = "1080p", quality = "BDRip")
        ),
        // Test case: Number in title
        TestCase(
            filename = "Deadpool.2.2018.1080p.BluRay.3xRus.Ukr.Eng.NTb.mkv",
            expected = ParsedMovieInfo(title = "deadpool 2", year = "2018", resolution = "1080p", quality = "BluRay")
        ),
        // Test case: Year in parentheses
        TestCase(
            filename = "Ghostbusters. Frozen Empire (2024).1080p.mkv",
            expected = ParsedMovieInfo(title = "ghostbusters frozen empire", year = "2024", resolution = "1080p")
        ),
        // Test case: Underscore delimiters
        TestCase(
            filename = "Ghostbusters_Afterlife_2021_BDRip_1080p_by_Dalemake.mkv",
            expected = ParsedMovieInfo(title = "ghostbusters afterlife", year = "2021", resolution = "1080p", quality = "BDRip")
        ),
        // Test case: Year in parentheses followed by bracketed metadata
        TestCase(
            filename = "King Arthur (2004) - [1080p] [Extended] [Director's Cut].mkv",
            expected = ParsedMovieInfo(title = "king arthur", year = "2004", resolution = "1080p")
        ),
        // Test case: Hyphen in title
        TestCase(
            filename = "Mission Impossible - Dead Reckoning Part One (2023).mkv",
            expected = ParsedMovieInfo(title = "mission impossible dead reckoning part one", year = "2023")
        ),
        // Test case: Minimalist filename with only Title and Year
        TestCase(
            filename = "The.Naked.Gun.1988.mkv",
            expected = ParsedMovieInfo(title = "the naked gun", year = "1988")
        ),
        // Test case: No year, but contains a release group
        TestCase(
            filename = "Superman.1080p.rus.LostFilm.TV.mkv",
            expected = ParsedMovieInfo(title = "superman", year = null, resolution = "1080p", releaseGroup = "LostFilm")
        ),
        // Test case: Complex title with a colon, year in parentheses
        TestCase(
            filename = "The Lord of the Rings. The War of the Rohirrim (2024) WEB-DL.2160p.SDR.mkv",
            expected = ParsedMovieInfo(
                title = "the lord of the rings the war of the rohirrim",
                year = "2024",
                resolution = "2160p",
                quality = "WEB-DL"
            )
        ),
        // Test case: Edition tag 'Extended'
        TestCase(
            filename = "Who.Am.I.1998.Extended.WEB-DL.1080p.mkv",
            expected = ParsedMovieInfo(title = "who am i", year = "1998", resolution = "1080p", quality = "WEB-DL")
        ),
        // Test case: Hyphenated title (X-Men)
        TestCase(
            filename = "X-MEN.Dark.Phoenix.2019.BDRip.1080p.mkv",
            expected = ParsedMovieInfo(title = "x men dark phoenix", year = "2019", resolution = "1080p", quality = "BDRip")
        ),
        // Test case: Transliterated title with underscores
        TestCase(
            filename = "Trener_Karter_2005_BDRip_by_Dalemake.avi",
            expected = ParsedMovieInfo(title = "trener karter", year = "2005", quality = "BDRip")
        ),
        // Test case: New quality tag 'WEB-DLRip'
        TestCase(
            filename = "Ryzhaya.Sonya.2025.WEB-DLRip.AVC.mkv",
            expected = ParsedMovieInfo(title = "ryzhaya sonya", year = "2025", quality = "WEB-DLRip")
        ),
        // Test case: Robustness against unicode garbage characters
        TestCase(
            filename = "The.Ritual.2025.2160p.UHD.AMZN.WEB-DL.SDR.HEVC-□'$'\\235''е□'$'\\207''ипо□'$'\\200''□'$'\\203''к.mkv",
            expected = ParsedMovieInfo(title = "the ritual", year = "2025", resolution = "2160p", quality = "WEB-DL")
        ),
    )

    @Test
    fun `parses list of movies correctly`() {
        movies.forEach { (filename, expected) ->
            val actual = MovieFilenameParser.parse(filename)

            // Use assertTrue for a more detailed custom message on failure
            assertTrue(
                actual = (actual == expected),
                message = """
            Test failed for filename: '$filename'
            -------------------------------------------------
            Expected: $expected
            Actual:   $actual
            -------------------------------------------------
            """.trimIndent(),
            )
        }
    }
}