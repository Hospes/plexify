package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
            path("Inception {imdb-tt1375666}", "Inception.mkv"),
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
            path("The Boys {imdb-tt1190634}", "Season 03", "The Boys - S03E01 - Payback.mkv"),
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

    // --- Trailing dots and spaces ---

    @Test
    fun `trims a trailing dot from a folder`() {
        val swat = CanonicalMedia.Episode(CanonicalMedia.TvShow(title = "S.W.A.T.", year = null, tmdbId = "71790"), 1, 1, "Pilot")

        assertEquals(
            path("S.W.A.T", "Season 01", "S.W.A.T. - S01E01 - Pilot.mkv"),
            formatter.formatEpisodePath(
                NamingStrategy.Plex.tvShowFolderTemplate, NamingStrategy.Plex.seasonFolderTemplate, NamingStrategy.Plex.episodeFileTemplate,
                swat, parsedEpisode, source,
            ).toString(),
        )
    }

    @Test
    fun `trims trailing dots and spaces from every folder of a custom template`() {
        val whatIf = CanonicalMedia.Movie(title = "What If...", year = null, tmdbId = "1")
        val template = NamingStrategy.Custom("{CleanTitle} [({imdbid})]/{CleanTitle}/{CleanTitle}{version}.{ext}")

        assertEquals(
            path("What If", "What If", "What If....mkv"),
            formatter.formatMoviePath(template.movieFolderTemplate, template.movieFileTemplate, whatIf, parsedMovie, source).toString(),
        )
    }

    @Test
    fun `trims a trailing dot from a file without an extension`() {
        val template = NamingStrategy.Custom("{CleanTitle}/{CleanTitle}.{ext}")

        assertEquals(
            path("Inception", "Inception"),
            formatter.formatMoviePath(template.movieFolderTemplate, template.movieFileTemplate, movie(null), parsedMovie, Path("Inception")).toString(),
        )
    }

    // --- Names over 255 bytes ---

    private val show = CanonicalMedia.TvShow(title = "Show", year = 2020, tmdbId = "3")
    private val parsedTwelve = ParsedMediaInfo.Episode(showTitle = "show", season = 1, episode = 1, lastEpisode = 12, year = null, resolution = "720p", quality = "WEB-DL")
    private val twelveSource = Path("Show.S01E01-E12.720p.mkv")

    private fun twelveEpisodes(show: CanonicalMedia.TvShow = this.show, title: (Int) -> String) =
        CanonicalMedia.Episode(show, season = 1, episode = 1, title = (1..12).joinToString(" & ", transform = title), lastEpisode = 12)

    private fun renderTwelve(media: CanonicalMedia.Episode): List<String> {
        val strategy = NamingStrategy.Jellyfin
        val path = formatter.formatEpisodePath(strategy.tvShowFolderTemplate, strategy.seasonFolderTemplate, strategy.episodeFileTemplate, media, parsedTwelve, twelveSource)
        return generateSequence(path) { it.parent }.map { it.name }.toList().reversed()
    }

    private val String.bytes get() = encodeToByteArray().size

    @Test
    fun `cuts the episode title of a name over 255 bytes`() {
        val name = renderTwelve(twelveEpisodes { "The Rather Long Episode Title Number $it" }).last()

        assertTrue(name.bytes <= 255, "${name.bytes} bytes: $name")
        assertTrue(name.startsWith("Show (2020) - S01E01-E12 - The Rather Long Episode Title Number 1 & "), name)
        assertTrue(name.endsWith("… - [720p] [WEB-DL].mkv"), name)
        // Cut at a word, without a dangling " &" before the ellipsis.
        assertTrue(Regex("""Number \d+… - """) in name, name)
    }

    @Test
    fun `a shortened name parses back to the same episodes`() {
        val name = renderTwelve(twelveEpisodes { "The Rather Long Episode Title Number $it" }).last()

        val reparsed = MediaFilenameParser.parse(name, "Season 01") as ParsedMediaInfo.Episode

        assertEquals("show", reparsed.showTitle)
        assertEquals(1, reparsed.season)
        assertEquals(1, reparsed.episode)
        assertEquals(12, reparsed.lastEpisode)
        assertEquals("720p", reparsed.resolution)
    }

    @Test
    fun `counts the length of a name in UTF-8 bytes`() {
        // Cyrillic letters are 2 bytes each: uncut, the name is 241 characters but 361 bytes.
        val name = renderTwelve(twelveEpisodes { "Длинное имя $it" }).last()

        assertTrue(name.bytes <= 255, "${name.bytes} bytes: $name")
        assertTrue(name.endsWith("… - [720p] [WEB-DL].mkv"), name)
    }

    @Test
    fun `never splits a character made of two UTF-16 units`() {
        val name = renderTwelve(twelveEpisodes { "🎬".repeat(10) }).last()

        assertTrue(name.bytes <= 255, "${name.bytes} bytes: $name")
        assertEquals(name, name.encodeToByteArray().decodeToString())
    }

    @Test
    fun `cuts a show title over 255 bytes in the folder and the file`() {
        val longShow = CanonicalMedia.TvShow(title = "Show ".repeat(60).trim(), year = 2020, tmdbId = "3")
        val segments = renderTwelve(twelveEpisodes(longShow) { "Part $it" })

        segments.forEach { assertTrue(it.bytes <= 255, "${it.bytes} bytes: $it") }
        assertTrue(segments[0].endsWith("… (2020) [tmdbid-3]"), segments[0])
        assertEquals("Season 01", segments[1])
        assertTrue(segments[2].endsWith(" - [720p] [WEB-DL].mkv"), segments[2])
        // No room is left for the episode titles: an ellipsis stands in for them.
        assertTrue(Regex("""… \(2020\) - S01E01-E12 - … - \[720p]""") in segments[2], segments[2])
    }

    @Test
    fun `cuts the literal text of a custom template and keeps the extension`() {
        val template = NamingStrategy.Custom("{CleanTitle}/${"x".repeat(300)}.{ext}")

        val file = formatter.formatMoviePath(template.movieFolderTemplate, template.movieFileTemplate, movie(2010), parsedMovie, source).name

        assertEquals("x".repeat(251) + ".mkv", file)
    }

    @Test
    fun `leaves a name of exactly 255 bytes alone`() {
        val template = NamingStrategy.Custom("{CleanTitle}/${"x".repeat(251)}.{ext}")

        val file = formatter.formatMoviePath(template.movieFolderTemplate, template.movieFileTemplate, movie(2010), parsedMovie, source).name

        assertEquals("x".repeat(251) + ".mkv", file)
    }

    @Test
    fun `cuts a placeholder used twice by half the excess at each place`() {
        val longTitle = CanonicalMedia.Movie(title = "Word ".repeat(40).trim(), year = 2010, tmdbId = "1")
        val template = NamingStrategy.Custom("{CleanTitle}/{CleanTitle} - {CleanTitle}.{ext}")

        val file = formatter.formatMoviePath(template.movieFolderTemplate, template.movieFileTemplate, longTitle, parsedMovie, source).name

        assertTrue(file.bytes in 240..255, "${file.bytes} bytes: $file")
        assertTrue(Regex("""^Word( Word)*… - Word( Word)*…\.mkv$""").matches(file), file)
    }

    @Test
    fun `fails with a reason when the file name renders empty`() {
        val nothing = CanonicalMedia.Movie(title = "???", year = null, tmdbId = "1")

        val error = assertFailsWith<IllegalArgumentException> {
            formatter.formatMoviePath(NamingStrategy.Plex.movieFolderTemplate, NamingStrategy.Plex.movieFileTemplate, nothing, parsedMovie, Path("noext"))
        }
        assertEquals("The naming template renders an empty file name", error.message)
    }

    // --- Characters invalid in file names ---

    private fun movieNamed(title: String) = CanonicalMedia.Movie(title = title, year = 2011, tmdbId = "1")

    private fun cleanMovieTitle(title: String): String =
        formatter.formatMoviePath("{CleanTitle}", "{CleanTitle}.{ext}", movieNamed(title), parsedMovie, source).name.substringBeforeLast('.')

    private fun cleanEpisodeTitle(title: String): String {
        val episode = CanonicalMedia.Episode(breakingBad, season = 1, episode = 1, title = title)
        return formatter.formatEpisodePath("{CleanTitle}", "Season {season:2}", "{episodeTitle}.{ext}", episode, parsedRange, rangeSource)
            .name.substringBeforeLast('.')
    }

    @Test
    fun `a slash between words becomes a hyphen`() {
        assertEquals("Fate-Zero", cleanMovieTitle("Fate/Zero"))
        assertEquals("AC-DC Let There Be Rock", cleanMovieTitle("AC/DC: Let There Be Rock"))
        assertEquals("Part 1-2", cleanEpisodeTitle("Part 1/2"))
        assertEquals("Fate-Zero", cleanMovieTitle("""Fate\Zero"""))
    }

    @Test
    fun `slashes in a row become one hyphen`() {
        assertEquals(".hack-SIGN", cleanMovieTitle(".hack//SIGN"))
        assertEquals("a-b", cleanEpisodeTitle("""a/\b"""))
    }

    @Test
    fun `a control character becomes a space`() {
        assertEquals("Tab Title", cleanMovieTitle("Tab\tTitle"))
        assertEquals("Ctrl X", cleanEpisodeTitle("Ctrl\u0001X"))
        assertEquals("Line Break", cleanMovieTitle("Line\r\nBreak"))
    }

    @Test
    fun `a slash with spaces around it becomes a space`() {
        assertEquals("Love Death and Robots", cleanMovieTitle("Love / Death and Robots"))
        assertEquals("Either Or", cleanEpisodeTitle("Either /Or"))
    }

    @Test
    fun `a colon becomes a space`() {
        assertEquals("Avatar The Way of Water", cleanMovieTitle("Avatar: The Way of Water"))
        assertEquals("Re ZERO -Starting Life in Another World-", cleanMovieTitle("Re:ZERO -Starting Life in Another World-"))
        assertEquals("Chapter One The Hellfire Club", cleanEpisodeTitle("Chapter One: The Hellfire Club"))
    }

    @Test
    fun `a vertical bar becomes a space`() {
        assertEquals("Foo Bar", cleanMovieTitle("Foo|Bar"))
        assertEquals("Foo Bar", cleanMovieTitle("Foo | Bar"))
    }

    @Test
    fun `question marks asterisks quotes and angle brackets are dropped`() {
        assertEquals("What If...", cleanMovieTitle("What If...?"))
        assertEquals("MASH", cleanMovieTitle("M*A*S*H"))
        assertEquals("The Bad Guys", cleanEpisodeTitle("The \"Bad\" Guys"))
        assertEquals("Hearts", cleanMovieTitle("<Hearts>"))
    }

    @Test
    fun `a name with a replaced slash parses back to the title`() {
        val show = CanonicalMedia.TvShow(title = "Fate/Zero", year = 2011, tmdbId = "45845")
        val episode = CanonicalMedia.Episode(show, season = 1, episode = 1, title = "Part 1/2")

        val path = formatter.formatEpisodePath(
            NamingStrategy.Jellyfin.tvShowFolderTemplate, NamingStrategy.Jellyfin.seasonFolderTemplate,
            NamingStrategy.Jellyfin.episodeFileTemplate, episode, parsedRange, rangeSource,
        )
        assertEquals("Fate-Zero (2011) - S01E01 - Part 1-2 - [720p].mkv", path.name)

        val reparsed = MediaFilenameParser.parse(path.name, "Season 01") as ParsedMediaInfo.Episode
        assertEquals("fate zero", reparsed.showTitle)
        assertEquals(1, reparsed.season)
        assertEquals(1, reparsed.episode)
        assertEquals(null, reparsed.lastEpisode)
        assertEquals("2011", reparsed.year)
    }

    @Test
    fun `a movie name with a replaced slash parses back to the title`() {
        val name = NamingStrategy.Jellyfin.renderNamed(CanonicalMedia.Movie(title = "Fate/stay night: Heaven's Feel I. presage flower", year = 2017, tmdbId = "390634"))
        assertEquals("Fate-stay night Heaven's Feel I. presage flower (2017) [tmdbid-390634].mkv", name)

        val reparsed = MediaFilenameParser.parse(name) as ParsedMediaInfo.Movie
        assertEquals("fate stay night heaven's feel i presage flower", reparsed.title)
        assertEquals("2017", reparsed.year)
    }

    private fun NamingStrategy.renderNamed(media: CanonicalMedia.Movie): String =
        formatter.formatMoviePath(movieFolderTemplate, movieFileTemplate, media, parsedMovie, Path("x.mkv")).name

    // --- Curly-brace groups ---

    /** Renders [template] as a movie folder name. */
    private fun folder(template: String, media: CanonicalMedia.Movie = movie(2010)): String =
        formatter.formatMoviePath(template, "x.{ext}", media, parsedMovie, source).parent!!.name

    private val noImdb = movie(2010).copy(imdbId = null)

    @Test
    fun `plex tags movie folders with the imdb id in curly braces`() {
        assertEquals(
            path("Inception (2010) {imdb-tt1375666}", "Inception (2010).mkv"),
            NamingStrategy.Plex.renderMovie(2010),
        )
    }

    @Test
    fun `plex tags show folders with the imdb id in curly braces`() {
        assertEquals(
            path("The Boys (2019) {imdb-tt1190634}", "Season 03", "The Boys (2019) - S03E01 - Payback.mkv"),
            NamingStrategy.Plex.renderEpisode(2019),
        )
    }

    @Test
    fun `plex leaves the tag out without an imdb id`() {
        val plex = NamingStrategy.Plex
        assertEquals(
            path("Inception (2010)", "Inception (2010).mkv"),
            formatter.formatMoviePath(plex.movieFolderTemplate, plex.movieFileTemplate, noImdb, parsedMovie, source).toString(),
        )
        val show = episode(2019).let { it.copy(show = it.show.copy(imdbId = null)) }
        assertEquals(
            path("The Boys (2019)", "Season 03", "The Boys (2019) - S03E01 - Payback.mkv"),
            formatter.formatEpisodePath(plex.tvShowFolderTemplate, plex.seasonFolderTemplate, plex.episodeFileTemplate, show, parsedEpisode, source)
                .toString(),
        )
    }

    @Test
    fun `plex template still asks for the imdb id`() {
        assertTrue("imdbid" in NamingStrategy.Plex.requiredMetadataFields())
    }

    @Test
    fun `a plex folder name parses back to the title and year`() {
        val reparsed = MediaFilenameParser.parse(folder(NamingStrategy.Plex.movieFolderTemplate)) as ParsedMediaInfo.Movie
        assertEquals("inception", reparsed.title)
        assertEquals("2010", reparsed.year)
    }

    @Test
    fun `a brace group keeps its braces when its placeholder has a value`() {
        assertEquals("Inception {imdb-tt1375666}", folder("{CleanTitle} {imdb-{imdbid}}"))
    }

    @Test
    fun `a brace group is dropped when its placeholder has no value`() {
        assertEquals("Inception (2010)", folder("{CleanTitle} ({year}) {imdb-{imdbid}}", noImdb))
    }

    @Test
    fun `brace groups are checked one by one`() {
        val template = "{CleanTitle} {imdb-{imdbid}} {tmdb-{tmdbid}}"
        assertEquals("Inception {imdb-tt1375666} {tmdb-27205}", folder(template))
        assertEquals("Inception {tmdb-27205}", folder(template, noImdb))
    }

    @Test
    fun `a brace group may hold a padded placeholder`() {
        val path = formatter.formatEpisodePath("{CleanTitle}", "{S{season:2}}", "{episode}.{ext}", episode(2019), parsedEpisode, source)
        assertEquals(path("The Boys", "{S03}", "1.mkv"), path.toString())
    }

    @Test
    fun `a square block holding a brace group follows the brace group's placeholder`() {
        val template = "{CleanTitle} [id {imdb-{imdbid}}]"
        assertEquals("Inception [id {imdb-tt1375666}]", folder(template))
        assertEquals("Inception", folder(template, noImdb))
    }

    @Test
    fun `placeholders side by side are not a brace group`() {
        assertEquals("Inception2010", folder("{CleanTitle}{year}"))
        assertEquals("Inception2010", folder("{CleanTitle}{year}", noImdb))
    }

    @Test
    fun `a brace group with several placeholders needs a value for each`() {
        val template = "{CleanTitle} {({year}) imdb-{imdbid}}"
        assertEquals("Inception {(2010) imdb-tt1375666}", folder(template))
        assertEquals("Inception", folder(template, noImdb))
        assertEquals("Inception", folder(template, movie(null)))
    }
}
