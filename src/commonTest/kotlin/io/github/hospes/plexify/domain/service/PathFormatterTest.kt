package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class PathFormatterTest {

    private val formatter = PathFormatter()
    private val source = Path("Some.Release.1080p.mkv")

    private fun movie(year: Int?) = CanonicalMedia.Movie(title = "Inception", year = year, imdbId = "tt1375666", tmdbId = "27205")
    private val parsedMovie = ParsedMediaInfo.Movie(title = "Inception", year = null)

    private fun episode(year: Int?) = CanonicalMedia.Episode(
        show = CanonicalMedia.TvShow(title = "The Boys", year = year, imdbId = "tt1190634", tmdbId = "76479"),
        season = 3,
        episode = 1,
        title = "Payback",
    )
    private val parsedEpisode = ParsedMediaInfo.Episode(showTitle = "The Boys", season = 3, episode = 1, year = null)

    private fun NamingStrategy.renderMovie(year: Int?): String =
        formatter.formatMoviePath(movieFolderTemplate, movieFileTemplate, movie(year), parsedMovie, source).toString()

    private fun NamingStrategy.renderEpisode(year: Int?): String =
        formatter.formatEpisodePath(tvShowFolderTemplate, seasonFolderTemplate, episodeFileTemplate, episode(year), parsedEpisode, source)
            .toString()

    private fun path(vararg parts: String) = Path(parts.first(), *parts.drop(1).toTypedArray()).toString()

    @Test
    fun `jellyfin movie with year`() {
        assertEquals(
            path("Inception (2010) [tmdbid-27205]", "Inception (2010) [tmdbid-27205].mkv"),
            NamingStrategy.Jellyfin.renderMovie(2010),
        )
    }

    @Test
    fun `jellyfin movie without year drops the parentheses`() {
        assertEquals(
            path("Inception [tmdbid-27205]", "Inception [tmdbid-27205].mkv"),
            NamingStrategy.Jellyfin.renderMovie(null),
        )
    }

    @Test
    fun `plex movie without year leaves no space before the extension`() {
        assertEquals(
            path("Inception [imdbid-tt1375666]", "Inception.mkv"),
            NamingStrategy.Plex.renderMovie(null),
        )
    }

    @Test
    fun `jellyfin episode with year`() {
        assertEquals(
            path("The Boys (2019) [tmdbid-76479]", "Season 03", "The Boys (2019) - S03E01 - Payback.mkv"),
            NamingStrategy.Jellyfin.renderEpisode(2019),
        )
    }

    @Test
    fun `episode without year drops the parentheses`() {
        assertEquals(
            path("The Boys [tmdbid-76479]", "Season 03", "The Boys - S03E01 - Payback.mkv"),
            NamingStrategy.Jellyfin.renderEpisode(null),
        )
        assertEquals(
            path("The Boys [imdbid-tt1190634]", "Season 03", "The Boys - S03E01 - Payback.mkv"),
            NamingStrategy.Plex.renderEpisode(null),
        )
    }

    @Test
    fun `custom template without year drops the parentheses`() {
        assertEquals(
            path("Inception", "Inception.mkv"),
            NamingStrategy.Custom("{CleanTitle} ({year})/{CleanTitle} ({year}).{ext}").renderMovie(null),
        )
    }

    @Test
    fun `parentheses with a value are kept`() {
        assertEquals(
            path("Inception (2010)", "Inception (2010).mkv"),
            NamingStrategy.Custom("{CleanTitle} ({year})/{CleanTitle} ({year}).{ext}").renderMovie(2010),
        )
    }

    // --- Multi-episode files ---

    private val breakingBad = CanonicalMedia.TvShow(title = "Breaking Bad", year = 2008, tmdbId = "1396", imdbId = "tt0903747")
    private val parsedRange = ParsedMediaInfo.Episode(showTitle = "breaking bad", season = 1, episode = 1, lastEpisode = 2, year = null, resolution = "720p")
    private val rangeSource = Path("Breaking.Bad.S01E01E02.720p.mkv")

    private fun NamingStrategy.renderEpisodeFile(media: CanonicalMedia.Episode): String =
        formatter.formatEpisodePath(tvShowFolderTemplate, seasonFolderTemplate, episodeFileTemplate, media, parsedRange, rangeSource).name

    @Test
    fun `names a single episode without a range`() {
        val episode = CanonicalMedia.Episode(breakingBad, season = 1, episode = 1, title = "Pilot")

        assertEquals("Breaking Bad (2008) - S01E01 - Pilot - [720p].mkv", NamingStrategy.Jellyfin.renderEpisodeFile(episode))
    }

    @Test
    fun `names a multi-episode file with its range`() {
        val episode = CanonicalMedia.Episode(breakingBad, season = 1, episode = 1, title = "Pilot & Cat's in the Bag", lastEpisode = 2)

        val expected = "Breaking Bad (2008) - S01E01-E02 - Pilot & Cat's in the Bag - [720p].mkv"
        assertEquals(expected, NamingStrategy.Jellyfin.renderEpisodeFile(episode))
        assertEquals(expected, NamingStrategy.Plex.renderEpisodeFile(episode))
    }

    @Test
    fun `a range name parses back to the same range`() {
        val episode = CanonicalMedia.Episode(breakingBad, season = 1, episode = 1, title = "Pilot & Cat's in the Bag", lastEpisode = 2)

        val reparsed = MediaFilenameParser.parse(NamingStrategy.Jellyfin.renderEpisodeFile(episode), "Season 01") as ParsedMediaInfo.Episode

        assertEquals(1, reparsed.episode)
        assertEquals(2, reparsed.lastEpisode)
        assertEquals("breaking bad", reparsed.showTitle)
    }
}
