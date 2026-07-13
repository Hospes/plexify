package io.github.hospes.plexify.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ParsedMediaInfoOverridesTest {

    private val episode = ParsedMediaInfo.Episode(showTitle = "hametsu no oukoku", season = null, episode = 3, year = null)
    private val movie = ParsedMediaInfo.Movie(title = "some cryptic rip", year = "2020")

    @Test
    fun `episode gets title and season and year overrides`() {
        val result = episode.withOverrides(title = "The Kingdoms of Ruin", season = 2, year = "2023") as ParsedMediaInfo.Episode

        assertEquals("The Kingdoms of Ruin", result.showTitle)
        assertEquals(2, result.season)
        assertEquals(3, result.episode)
        assertEquals("2023", result.year)
    }

    @Test
    fun `null overrides keep parsed values`() {
        assertEquals(episode, episode.withOverrides(title = null, season = null, year = null))
        assertEquals(movie, movie.withOverrides(title = null, season = null, year = null))
    }

    @Test
    fun `movie gets title and year overrides and ignores season`() {
        val result = movie.withOverrides(title = "Actual Movie", season = 5, year = "2019") as ParsedMediaInfo.Movie

        assertEquals("Actual Movie", result.title)
        assertEquals("2019", result.year)
    }
}
