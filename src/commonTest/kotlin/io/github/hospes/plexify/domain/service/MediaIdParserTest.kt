package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.MediaId
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The movie or show ID a path names, which picks the match instead of a title search. */
class MediaIdParserTest {

    private fun tmdb(id: String, fromTitle: Boolean = false) = MediaId(MediaId.Database.TMDB, id, fromTitle)
    private fun imdb(id: String, fromTitle: Boolean = false) = MediaId(MediaId.Database.IMDB, id, fromTitle)

    @Test
    fun `a Jellyfin movie names its TMDB ID in the file name`() {
        val parsed = MediaFilenameParser.parse("Inception (2010) [tmdbid-27205] - [1080p] [BluRay].mkv", "Inception (2010) [tmdbid-27205]")

        assertIs<ParsedMediaInfo.Movie>(parsed)
        assertEquals("inception", parsed.title)
        assertEquals(tmdb("27205"), parsed.mediaId)
    }

    @Test
    fun `a Plex movie names its IMDb ID in its folder`() {
        val parsed = MediaFilenameParser.parse("The Matrix (1999) - [1080p].mkv", "The Matrix (1999) {imdb-tt0133093}")

        assertEquals(imdb("tt0133093"), parsed.mediaId)
    }

    @Test
    fun `an episode names its show's ID in the show folder above its season folder`() {
        val jellyfin = MediaFilenameParser.parse("hack (2002) - S01E01 - Role Play.mkv", "Season 01", "hack (2002) [tmdbid-8864]")
        val plex = MediaFilenameParser.parse("hack (2002) - S01E01 - Role Play.mkv", "Season 01", "hack (2002) {imdb-tt0361140}")

        assertIs<ParsedMediaInfo.Episode>(jellyfin)
        assertEquals("hack", jellyfin.showTitle)
        assertEquals(tmdb("8864"), jellyfin.mediaId)
        assertEquals(imdb("tt0361140"), plex.mediaId)
    }

    @Test
    fun `an episode in its show folder names the show's ID there`() {
        val parsed = MediaFilenameParser.parse("Show.S01E01.1080p.mkv", "Show (2020) [tmdbid-42]", "downloads")

        assertEquals(tmdb("42"), parsed.mediaId)
    }

    @Test
    fun `the folder above a release folder is not the show's`() {
        val parsed = MediaFilenameParser.parse("Show.S01E01.1080p.mkv", "Show.S01.1080p", "Other Show (2020) [tmdbid-42]")

        assertNull(parsed.mediaId)
    }

    @Test
    fun `a movie doesn't look above its folder`() {
        val parsed = MediaFilenameParser.parse("Movie.2010.1080p.mkv", "Season 01", "Show (2020) [tmdbid-42]")

        assertNull(parsed.mediaId)
    }

    @Test
    fun `a show folder's tag is not a movie's in it`() {
        val movie = MediaFilenameParser.parse("El Camino A Breaking Bad Movie.mkv", "Breaking Bad (2008) [tmdbid-1396]")
        val extra = MediaFilenameParser.parse("Breaking Bad - Behind the Scenes.mkv", "Breaking Bad (2008) [tmdbid-1396]")

        assertNull(movie.mediaId)
        assertNull(extra.mediaId)
    }

    @Test
    fun `a movie folder's tag is not its sequel's`() {
        val parsed = MediaFilenameParser.parse("Kill Bill - Part 2.mkv", "Kill Bill (2003) [tmdbid-24]")

        assertNull(parsed.mediaId)
    }

    @Test
    fun `a release folder that names another title gives no tag`() {
        val parsed = MediaFilenameParser.parse("Breaking.Bad.S01E01.720p.mkv", "Breaking Bad S01 720p [tmdbid-1396]")

        assertNull(parsed.mediaId)
    }

    @Test
    fun `a shortened title still names its folder`() {
        val long = "An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title"
        val parsed = MediaFilenameParser.parse("$long That Keeps… (2019) - S01E01 - Pilot.mkv", "Season 01", "$long That Keeps On Going An Extremely… (2019) [tmdbid-7]")

        assertEquals(tmdb("7"), parsed.mediaId)
    }

    @Test
    fun `the tag nearest the file wins`() {
        val parsed = MediaFilenameParser.parse("Show (2020) [tmdbid-1] - S01E01.mkv", "Season 01", "Show (2020) [tmdbid-2]")

        assertEquals(tmdb("1"), parsed.mediaId)
    }

    @Test
    fun `a TMDB tag wins over an IMDb tag`() {
        val parsed = MediaFilenameParser.parse("Movie (2010) {imdb-tt0000001} [tmdbid-27205].mkv")

        assertEquals(tmdb("27205"), parsed.mediaId)
    }

    @Test
    fun `Plex's and Jellyfin's other ID tags name the ID too`() {
        assertEquals(tmdb("603"), MediaFilenameParser.parse("The Matrix (1999) {tmdb-603}.mkv").mediaId)
        assertEquals(tmdb("603"), MediaFilenameParser.parse("The Matrix (1999) [tmdbid=603].mkv").mediaId)
        assertEquals(imdb("tt0133093"), MediaFilenameParser.parse("The Matrix (1999) [imdbid-tt0133093].mkv").mediaId)
        assertEquals(imdb("tt0133093"), MediaFilenameParser.parse("The Matrix (1999) [IMDBID-TT0133093].mkv").mediaId)
    }

    @Test
    fun `a TVDB tag or a malformed tag names no ID`() {
        assertNull(MediaFilenameParser.parse("Show (2020) [tvdbid-79099] - S01E01.mkv").mediaId)
        assertNull(MediaFilenameParser.parse("The Matrix (1999) [tmdbid-].mkv").mediaId)
        assertNull(MediaFilenameParser.parse("The Matrix (1999) {imdb-0133093}.mkv").mediaId)
    }

    @Test
    fun `a title plexify named by its ID names that ID`() {
        val movie = MediaFilenameParser.parse("tmdb-1721501 (2026) - [1080p] [WEB-DL].mkv", "tmdb-1721501 (2026)")
        val noYear = MediaFilenameParser.parse("imdb-tt0133093.mkv")
        val episode = MediaFilenameParser.parse("tmdb-123 (2020) - S01E01 - Pilot.mkv", "Season 01", "tmdb-123 (2020)")

        assertIs<ParsedMediaInfo.Movie>(movie)
        assertEquals("tmdb 1721501", movie.title)
        assertEquals(tmdb("1721501", fromTitle = true), movie.mediaId)
        assertEquals(imdb("tt0133093", fromTitle = true), noYear.mediaId)
        assertEquals(tmdb("123", fromTitle = true), episode.mediaId)
    }

    @Test
    fun `an ID tag wins over a title named by its ID`() {
        val jellyfin = MediaFilenameParser.parse("tmdb-1721501 (2026) [tmdbid-1721501] - [1080p].mkv")
        val plex = MediaFilenameParser.parse("tmdb-1721501 (2026) - [1080p].mkv", "tmdb-1721501 (2026) {imdb-tt0000001}")

        // The title is the ID, so it can't vouch for the tag's record either.
        assertEquals(tmdb("1721501", fromTitle = true), jellyfin.mediaId)
        assertEquals(imdb("tt0000001", fromTitle = true), plex.mediaId)
    }

    @Test
    fun `a title that only looks like plexify's ID name names no ID`() {
        assertNull(MediaFilenameParser.parse("TMDB-123 (2020).mkv").mediaId)
        assertNull(MediaFilenameParser.parse("tmdb.123.2020.1080p.mkv").mediaId)
        assertNull(MediaFilenameParser.parse("tmdb-123 Origins (2020).mkv").mediaId)
        assertNull(MediaFilenameParser.parse("The tmdb-123 (2020).mkv").mediaId)
    }
}
