package io.github.hospes.plexify.domain.service

import kotlin.test.Test
import kotlin.test.assertEquals

class MovieFilenameParserTest {

    @Test
    fun `parses title and year correctly`() {
        val filename = "The.Matrix.1999.1080p.BluRay.x264-YTS.mkv"
        val expected = ParsedMovieInfo(title = "the matrix", year = "1999", resolution = "1080p", quality = "BluRay")
        val actual = MovieFilenameParser.parse(filename)
        assertEquals(expected, actual)
    }

    @Test
    fun `parses title when no year is present`() {
        val filename = "Inception.720p.BrRip.x264.YIFY.mp4"
        val expected = ParsedMovieInfo(title = "inception", year = null, resolution = "720p", quality = "BrRip")
        val actual = MovieFilenameParser.parse(filename)
        assertEquals(expected, actual)
    }

    @Test
    fun `handles complex filenames with brackets and dashes`() {
        val filename = "Blade.Runner.2049.[2017].UHD.BluRay.2160p.x265-TERMiNAL.mkv"
        val expected = ParsedMovieInfo(title = "blade runner 2049", year = "2017", resolution = "2160p", quality = "BluRay")
        val actual = MovieFilenameParser.parse(filename)
        assertEquals(expected, actual)
    }
}