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

    /** Renders a movie with a "folder/file" template, to test the formatter on templates no preset uses. */
    private fun renderMovie(template: String, media: CanonicalMedia.Movie, sourceFile: Path = source): Path =
        formatter.formatMoviePath(template.substringBeforeLast('/'), template.substringAfterLast('/'), media, parsedMovie, sourceFile)

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
    fun `a year alone in parentheses is dropped without a value`() {
        assertEquals(
            path("Inception", "Inception.mkv"),
            renderMovie("{CleanTitle} ({year})/{CleanTitle} ({year}).{ext}", movie(null)).toString(),
        )
    }

    @Test
    fun `parentheses with a value are kept`() {
        assertEquals(
            path("Inception (2010)", "Inception (2010).mkv"),
            renderMovie("{CleanTitle} ({year})/{CleanTitle} ({year}).{ext}", movie(2010)).toString(),
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

    // --- Release group: the last {version} tag ---

    private fun renderMovieFile(strategy: NamingStrategy, parsed: ParsedMediaInfo.Movie, media: CanonicalMedia.Movie = movie(2010)): String =
        formatter.formatMoviePath(strategy.movieFolderTemplate, strategy.movieFileTemplate, media, parsed, source).name

    private fun renderJellyfinEpisode(media: CanonicalMedia.Episode, parsed: ParsedMediaInfo.Episode): String {
        val strategy = NamingStrategy.Jellyfin
        return formatter.formatEpisodePath(strategy.tvShowFolderTemplate, strategy.seasonFolderTemplate, strategy.episodeFileTemplate, media, parsed, source).name
    }

    private val scene = ParsedMediaInfo.Movie(title = "inception", year = "2010", resolution = "1080p", quality = "BluRay", releaseGroup = "YTS")

    @Test
    fun `closes the version tags of a movie with the release group`() {
        assertEquals("Inception (2010) [tmdbid-27205] - [1080p] [BluRay] [YTS].mkv", renderMovieFile(NamingStrategy.Jellyfin, scene))
        assertEquals("Inception (2010) - [1080p] [BluRay] [YTS].mkv", renderMovieFile(NamingStrategy.Plex, scene))
    }

    @Test
    fun `closes the version tags of an episode with the release group`() {
        val parsed = ParsedMediaInfo.Episode(showTitle = "the boys", season = 3, episode = 1, year = null, resolution = "1080p", quality = "WEB-DL", releaseGroup = "NTb")

        assertEquals("The Boys (2019) - S03E01 - Payback - [1080p] [WEB-DL] [NTb].mkv", renderJellyfinEpisode(episode(2019), parsed))
    }

    @Test
    fun `a release group alone makes the version tags`() {
        assertEquals("Inception (2010) [tmdbid-27205] - [SubsPlease].mkv", renderMovieFile(NamingStrategy.Jellyfin, parsedMovie.copy(releaseGroup = "SubsPlease")))
    }

    @Test
    fun `cleans the release group like a title`() {
        val parsed = parsedMovie.copy(resolution = "1080p", releaseGroup = "Foo:Bar?")

        assertEquals("Inception (2010) [tmdbid-27205] - [1080p] [Foo Bar].mkv", renderMovieFile(NamingStrategy.Jellyfin, parsed))
    }

    @Test
    fun `leaves out a release group that repeats another tag`() {
        val parsed = parsedMovie.copy(resolution = "1080p", releaseGroup = "1080P")

        assertEquals("Inception (2010) [tmdbid-27205] - [1080p].mkv", renderMovieFile(NamingStrategy.Jellyfin, parsed))
    }

    @Test
    fun `a movie name with a release group parses back to the same name`() {
        val releases = listOf(
            "Inception.2010.1080p.BluRay.x264-YTS.mkv",
            "Inception.2010.1080p.BluRay.x264.AAC5.1-[YTS.MX].mp4",
            "[Judas] Inception (2010) [1080p] [HEVC].mkv",
            "Inception.2010.Directors.Cut.2160p.BluRay.HDR10.x265-TERMiNAL.mkv",
            "Inception.2010.1080p.BluRay.mkv",
        )
        for (strategy in listOf(NamingStrategy.Jellyfin, NamingStrategy.Plex)) {
            for (release in releases) {
                val parsed = MediaFilenameParser.parse(release) as ParsedMediaInfo.Movie
                val name = renderMovieFile(strategy, parsed)
                val reparsed = MediaFilenameParser.parse(name) as ParsedMediaInfo.Movie

                assertEquals("inception", reparsed.title, name)
                assertEquals("2010", reparsed.year, name)
                assertEquals(parsed.releaseGroup, reparsed.releaseGroup, name)
                assertEquals(name, renderMovieFile(strategy, reparsed), "re-rendered from $release")
            }
        }
    }

    @Test
    fun `an episode name with a release group parses back to the same name`() {
        val felina = CanonicalMedia.Episode(breakingBad, season = 5, episode = 16, title = "Felina")
        val parsed = MediaFilenameParser.parse("Breaking.Bad.S05E16.Felina.1080p.BluRay.x264-DEMAND.mkv") as ParsedMediaInfo.Episode
        val name = renderJellyfinEpisode(felina, parsed)

        val reparsed = MediaFilenameParser.parse(name, "Season 05") as ParsedMediaInfo.Episode

        assertEquals("Breaking Bad (2008) - S05E16 - Felina - [1080p] [BluRay] [DEMAND].mkv", name)
        assertEquals("breaking bad", reparsed.showTitle)
        assertEquals("DEMAND", reparsed.releaseGroup)
        assertEquals(name, renderJellyfinEpisode(felina, reparsed))
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
    fun `trims trailing dots and spaces from a folder`() {
        val whatIf = CanonicalMedia.Movie(title = "What If...", year = null, tmdbId = "1")

        assertEquals(
            path("What If", "What If....mkv"),
            renderMovie("{CleanTitle} [({imdbid})]/{CleanTitle}{version}.{ext}", whatIf).toString(),
        )
    }

    @Test
    fun `trims a trailing dot from a file without an extension`() {
        assertEquals(
            path("Inception", "Inception"),
            renderMovie("{CleanTitle}/{CleanTitle}.{ext}", movie(null), Path("Inception")).toString(),
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
    fun `keeps the release group of a long name and cuts the episode title`() {
        val strategy = NamingStrategy.Jellyfin
        val media = twelveEpisodes { "The Rather Long Episode Title Number $it" }
        val name = formatter.formatEpisodePath(
            strategy.tvShowFolderTemplate, strategy.seasonFolderTemplate, strategy.episodeFileTemplate,
            media, parsedTwelve.copy(releaseGroup = "NTb"), twelveSource,
        ).name

        assertTrue(name.bytes <= 255, "${name.bytes} bytes: $name")
        assertTrue(name.endsWith("… - [720p] [WEB-DL] [NTb].mkv"), name)
    }

    @Test
    fun `drops the release group only when the name cannot fit it`() {
        val strategy = NamingStrategy.Jellyfin
        val media = twelveEpisodes { "The Rather Long Episode Title Number $it" }
        val name = formatter.formatEpisodePath(
            strategy.tvShowFolderTemplate, strategy.seasonFolderTemplate, strategy.episodeFileTemplate,
            media, parsedTwelve.copy(releaseGroup = "G".repeat(240)), twelveSource,
        ).name

        assertTrue(name.bytes <= 255, "${name.bytes} bytes: $name")
        assertTrue(name.endsWith("… - [720p] [WEB-DL].mkv"), name)
        assertTrue(name.startsWith("Show (2020) - S01E01-E12 - The Rather Long"), name)
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
    fun `cuts the literal text of a template and keeps the extension`() {
        val file = renderMovie("{CleanTitle}/${"x".repeat(300)}.{ext}", movie(2010)).name

        assertEquals("x".repeat(251) + ".mkv", file)
    }

    @Test
    fun `leaves a name of exactly 255 bytes alone`() {
        val file = renderMovie("{CleanTitle}/${"x".repeat(251)}.{ext}", movie(2010)).name

        assertEquals("x".repeat(251) + ".mkv", file)
    }

    @Test
    fun `cuts a placeholder used twice by half the excess at each place`() {
        val longTitle = CanonicalMedia.Movie(title = "Word ".repeat(40).trim(), year = 2010, tmdbId = "1")
        val file = renderMovie("{CleanTitle}/{CleanTitle} - {CleanTitle}.{ext}", longTitle).name

        assertTrue(file.bytes in 240..255, "${file.bytes} bytes: $file")
        assertTrue(Regex("""^Word( Word)*… - Word( Word)*…\.mkv$""").matches(file), file)
    }

    @Test
    fun `fails with a reason when the file name renders empty`() {
        val error = assertFailsWith<IllegalArgumentException> {
            formatter.formatMoviePath("{CleanTitle}", "{year}.{ext}", movie(null), parsedMovie, Path("noext"))
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

    // --- ID tags ---

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
    fun `a brace tag keeps its braces when its id has a value`() {
        assertEquals("Inception {imdb-tt1375666}", folder("{CleanTitle} {imdb-{imdbid}}"))
    }

    @Test
    fun `a brace tag is dropped when its id has no value`() {
        assertEquals("Inception (2010)", folder("{CleanTitle} ({year}) {imdb-{imdbid}}", noImdb))
    }

    @Test
    fun `tags are checked one by one`() {
        val template = "{CleanTitle} {imdb-{imdbid}} {tmdb-{tmdbid}}"
        assertEquals("Inception {imdb-tt1375666} {tmdb-27205}", folder(template))
        assertEquals("Inception {tmdb-27205}", folder(template, noImdb))
    }

    @Test
    fun `a square tag is dropped when its id has no value`() {
        val template = "{CleanTitle} [tmdbid-{tmdbid}]"
        assertEquals("Inception [tmdbid-27205]", folder(template))
        assertEquals("Inception", folder(template, movie(2010).copy(tmdbId = null)))
    }

    @Test
    fun `placeholders side by side are not a tag`() {
        assertEquals("Inception2010", folder("{CleanTitle}{year}"))
        assertEquals("Inception2010", folder("{CleanTitle}{year}", noImdb))
    }

    // --- Titles with nothing usable in a name ---

    // Real TMDB titles: movie 1721501 is "?" (2026, found by its alternative titles), show 315175 is "." (2012).
    private fun questionMark(year: Int?) = CanonicalMedia.Movie(title = "?", year = year, tmdbId = "1721501")
    private fun dot(year: Int?) = CanonicalMedia.Episode(
        show = CanonicalMedia.TvShow(title = ".", year = year, tmdbId = "315175"),
        season = 1,
        episode = 1,
        title = "Pilot",
    )

    private fun NamingStrategy.render(media: CanonicalMedia.Movie): String =
        formatter.formatMoviePath(movieFolderTemplate, movieFileTemplate, media, parsedMovie, source).toString()

    private fun NamingStrategy.render(media: CanonicalMedia.Episode): String =
        formatter.formatEpisodePath(tvShowFolderTemplate, seasonFolderTemplate, episodeFileTemplate, media, parsedEpisode, source).toString()

    @Test
    fun `plex names a movie without a usable title by its tmdb id`() {
        assertEquals(path("tmdb-1721501", "tmdb-1721501.mkv"), NamingStrategy.Plex.render(questionMark(null)))
        assertEquals(path("tmdb-1721501 (2026)", "tmdb-1721501 (2026).mkv"), NamingStrategy.Plex.render(questionMark(2026)))
    }

    @Test
    fun `plex names a show without a usable title by its tmdb id`() {
        assertEquals(path("tmdb-315175", "Season 01", "tmdb-315175 - S01E01 - Pilot.mkv"), NamingStrategy.Plex.render(dot(null)))
        assertEquals(
            path("tmdb-315175 (2012)", "Season 01", "tmdb-315175 (2012) - S01E01 - Pilot.mkv"),
            NamingStrategy.Plex.render(dot(2012)),
        )
    }

    @Test
    fun `jellyfin names a movie without a usable title by its tmdb id`() {
        assertEquals(
            path("tmdb-1721501 [tmdbid-1721501]", "tmdb-1721501 [tmdbid-1721501].mkv"),
            NamingStrategy.Jellyfin.render(questionMark(null)),
        )
        assertEquals(
            path("tmdb-1721501 (2026) [tmdbid-1721501]", "tmdb-1721501 (2026) [tmdbid-1721501].mkv"),
            NamingStrategy.Jellyfin.render(questionMark(2026)),
        )
    }

    @Test
    fun `jellyfin names a show without a usable title by its tmdb id`() {
        assertEquals(
            path("tmdb-315175 [tmdbid-315175]", "Season 01", "tmdb-315175 - S01E01 - Pilot.mkv"),
            NamingStrategy.Jellyfin.render(dot(null)),
        )
        assertEquals(
            path("tmdb-315175 (2012) [tmdbid-315175]", "Season 01", "tmdb-315175 (2012) - S01E01 - Pilot.mkv"),
            NamingStrategy.Jellyfin.render(dot(2012)),
        )
    }

    @Test
    fun `a title of dots spaces and dropped characters is not usable`() {
        for (title in listOf("...", ". .", "//", "::::::", "\" * \"", "?.", "|||")) {
            val media = CanonicalMedia.Movie(title = title, year = null, tmdbId = "1")
            assertEquals(path("tmdb-1", "tmdb-1.mkv"), NamingStrategy.Plex.render(media), title)
        }
    }

    @Test
    fun `falls back to the imdb id without a tmdb id`() {
        val media = CanonicalMedia.Movie(title = "?", year = null, imdbId = "tt23643874")
        assertEquals(path("imdb-tt23643874 {imdb-tt23643874}", "imdb-tt23643874.mkv"), NamingStrategy.Plex.render(media))
    }

    @Test
    fun `fails with a reason when a title has nothing usable and no id`() {
        val error = assertFailsWith<IllegalArgumentException> { NamingStrategy.Plex.render(CanonicalMedia.Movie(title = "?", year = 2026)) }
        assertEquals("The title \"?\" has nothing usable in a file name, and no ID to name it by", error.message)
    }

    @Test
    fun `titles with something usable keep their names`() {
        // Byte for byte the names these titles got before the fallback, including titles made of symbols alone.
        val names = listOf("$", "★", "!", "#", "()", "--", "_ _", "What If...?", "M*A*S*H", "Se7en", "Avatar: The Way of Water").map { title ->
            val media = CanonicalMedia.Movie(title = title, year = 2011, imdbId = "tt1", tmdbId = "1")
            val episode = CanonicalMedia.Episode(CanonicalMedia.TvShow(title = title, year = null, tmdbId = "2"), season = 1, episode = 2, title = "Two")
            listOf(NamingStrategy.Plex.render(media), NamingStrategy.Jellyfin.render(media), NamingStrategy.Plex.render(episode))
        }
        assertEquals(
            listOf(
                listOf(path("$ (2011) {imdb-tt1}", "$ (2011).mkv"), path("$ (2011) [tmdbid-1]", "$ (2011) [tmdbid-1].mkv"), path("$", "Season 01", "$ - S01E02 - Two.mkv")),
                listOf(path("★ (2011) {imdb-tt1}", "★ (2011).mkv"), path("★ (2011) [tmdbid-1]", "★ (2011) [tmdbid-1].mkv"), path("★", "Season 01", "★ - S01E02 - Two.mkv")),
                listOf(path("! (2011) {imdb-tt1}", "! (2011).mkv"), path("! (2011) [tmdbid-1]", "! (2011) [tmdbid-1].mkv"), path("!", "Season 01", "! - S01E02 - Two.mkv")),
                listOf(path("# (2011) {imdb-tt1}", "# (2011).mkv"), path("# (2011) [tmdbid-1]", "# (2011) [tmdbid-1].mkv"), path("#", "Season 01", "# - S01E02 - Two.mkv")),
                listOf(path("() (2011) {imdb-tt1}", "() (2011).mkv"), path("() (2011) [tmdbid-1]", "() (2011) [tmdbid-1].mkv"), path("()", "Season 01", "() - S01E02 - Two.mkv")),
                listOf(path("-- (2011) {imdb-tt1}", "-- (2011).mkv"), path("-- (2011) [tmdbid-1]", "-- (2011) [tmdbid-1].mkv"), path("--", "Season 01", "-- - S01E02 - Two.mkv")),
                listOf(path("_ _ (2011) {imdb-tt1}", "_ _ (2011).mkv"), path("_ _ (2011) [tmdbid-1]", "_ _ (2011) [tmdbid-1].mkv"), path("_ _", "Season 01", "_ _ - S01E02 - Two.mkv")),
                listOf(
                    path("What If... (2011) {imdb-tt1}", "What If... (2011).mkv"),
                    path("What If... (2011) [tmdbid-1]", "What If... (2011) [tmdbid-1].mkv"),
                    path("What If", "Season 01", "What If... - S01E02 - Two.mkv"),
                ),
                listOf(path("MASH (2011) {imdb-tt1}", "MASH (2011).mkv"), path("MASH (2011) [tmdbid-1]", "MASH (2011) [tmdbid-1].mkv"), path("MASH", "Season 01", "MASH - S01E02 - Two.mkv")),
                listOf(path("Se7en (2011) {imdb-tt1}", "Se7en (2011).mkv"), path("Se7en (2011) [tmdbid-1]", "Se7en (2011) [tmdbid-1].mkv"), path("Se7en", "Season 01", "Se7en - S01E02 - Two.mkv")),
                listOf(
                    path("Avatar The Way of Water (2011) {imdb-tt1}", "Avatar The Way of Water (2011).mkv"),
                    path("Avatar The Way of Water (2011) [tmdbid-1]", "Avatar The Way of Water (2011) [tmdbid-1].mkv"),
                    path("Avatar The Way of Water", "Season 01", "Avatar The Way of Water - S01E02 - Two.mkv"),
                ),
            ),
            names,
        )
    }
}
