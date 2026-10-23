package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The names the built-in templates give a varied set of movies and episodes. A change to the formatter that isn't
 * meant to rename anything must leave every line as it is.
 */
class BuiltInNamesTest {

    private val formatter = PathFormatter()
    private val strategies = listOf(NamingStrategy.Plex, NamingStrategy.Jellyfin)

    private class MovieCase(val media: CanonicalMedia.Movie, val parsed: ParsedMediaInfo.Movie, val source: String)
    private class EpisodeCase(val media: CanonicalMedia.Episode, val parsed: ParsedMediaInfo.Episode, val source: String)

    private fun movie(
        title: String,
        year: Int? = 2010,
        imdbId: String? = "tt1375666",
        tmdbId: String? = "27205",
        parsed: ParsedMediaInfo.Movie = ParsedMediaInfo.Movie(title = "x", year = null),
        source: String = "Some.Release.mkv",
    ) = MovieCase(CanonicalMedia.Movie(title, year, imdbId = imdbId, tmdbId = tmdbId), parsed, source)

    private val boys = CanonicalMedia.TvShow(title = "The Boys", year = 2019, imdbId = "tt1190634", tmdbId = "76479")

    private fun episode(
        title: String,
        show: CanonicalMedia.TvShow = boys,
        season: Int = 3,
        episode: Int = 1,
        lastEpisode: Int? = null,
        parsed: ParsedMediaInfo.Episode = ParsedMediaInfo.Episode(showTitle = "x", season = season, episode = episode, year = null),
        source: String = "Some.Release.mkv",
    ) = EpisodeCase(CanonicalMedia.Episode(show, season, episode, title, lastEpisode), parsed, source)

    private val scene = ParsedMediaInfo.Movie(title = "x", year = null, resolution = "1080p", quality = "BluRay", releaseGroup = "YTS")
    private val webEpisode = ParsedMediaInfo.Episode(
        showTitle = "x", season = 3, episode = 1, year = null, resolution = "720p", quality = "WEB-DL", releaseGroup = "NTb",
    )

    private val movies = listOf(
        movie("Inception"),
        movie("Inception", parsed = scene, source = "Inception.2010.1080p.BluRay-YTS.mkv"),
        movie("Inception", year = null),
        movie("Inception", imdbId = null),
        movie("Inception", tmdbId = null),
        movie("Inception", year = null, imdbId = null, tmdbId = null),
        movie("Inception", source = "Inception"),
        movie(
            "Blade Runner",
            year = 1982,
            parsed = ParsedMediaInfo.Movie(
                title = "x", year = null, resolution = "2160p", quality = "BluRay", hdr = "HDR10", edition = "Final Cut", releaseGroup = "[FraMeSToR]",
            ),
        ),
        movie("Inception", parsed = ParsedMediaInfo.Movie(title = "x", year = null, resolution = "1080p", releaseGroup = "1080p")),
        movie("Inception", parsed = ParsedMediaInfo.Movie(title = "x", year = null, releaseGroup = "Group: A/B")),
        movie("Avatar: The Way of Water", year = 2022),
        movie("M*A*S*H <\"Pilot\"> | Part 1/2?", year = 1972),
        movie("Fate/Zero", year = 2011),
        movie(".hack//SIGN", year = 2002),
        movie("What If...", year = 1999),
        movie("Re:ZERO\tStarting Life", year = 2016),
        movie("???", year = 2010),
        movie("A Rather Long Movie Title That Goes On ".repeat(8).trim(), parsed = scene),
        movie("とても長い映画のタイトル".repeat(8), year = 2020, parsed = scene),
        movie("😀".repeat(70), year = 2020),
        movie("Inception", parsed = ParsedMediaInfo.Movie(title = "x", year = null, edition = "E".repeat(300), releaseGroup = "GRP")),
        movie("Inception", parsed = ParsedMediaInfo.Movie(title = "x", year = null, edition = "E".repeat(233), releaseGroup = "GRP")),
        movie("[REC]", year = 2007),
    )

    private val longShow = boys.copy(title = "An Extremely Long Show Title That Keeps On Going ".repeat(6).trim())

    private val episodes = listOf(
        episode("Payback"),
        episode("Payback", parsed = webEpisode, source = "The.Boys.S03E01.720p.WEB-DL-NTb.mkv"),
        episode("Payback & The Only Man in the Sky", lastEpisode = 2, parsed = webEpisode),
        episode("Payback", show = boys.copy(year = null)),
        episode("Payback", show = boys.copy(imdbId = null)),
        episode("Payback", show = boys.copy(tmdbId = null)),
        episode("Payback", show = boys.copy(year = null, imdbId = null, tmdbId = null)),
        episode("Who's Afraid: Of/The \"Big\" <Bad> Wolf?"),
        episode(""),
        episode("Special", season = 0, episode = 112),
        episode("Pilot", show = boys.copy(title = "Marvel's Agents of S.H.I.E.L.D.", year = 2013)),
        episode(
            (1..12).joinToString(" & ") { "The Rather Long Episode Title Number $it" },
            season = 1, episode = 1, lastEpisode = 12, parsed = webEpisode,
        ),
        episode(
            (1..12).joinToString(" & ") { "Episode $it" },
            show = longShow, season = 1, episode = 1, lastEpisode = 12, parsed = webEpisode,
        ),
        episode("Pilot", show = longShow, parsed = webEpisode),
        episode(
            "Long Words Here ".repeat(20).trim(),
            show = longShow, season = 1, episode = 1, lastEpisode = 112,
            parsed = webEpisode.copy(edition = "Extended Directors Cut Remastered Anniversary Edition", releaseGroup = "SomeVeryLongReleaseGroupName"),
        ),
    )

    private fun Path.segments(): String = generateSequence(this) { it.parent }.map { it.name }.toList().reversed().joinToString("/")

    private fun render(): List<String> = buildList {
        for (strategy in strategies) {
            for (case in movies) {
                val path = formatter.formatMoviePath(strategy.movieFolderTemplate, strategy.movieFileTemplate, case.media, case.parsed, Path(case.source))
                add("${strategy.name} | ${path.segments()}")
            }
            for (case in episodes) {
                val path = formatter.formatEpisodePath(
                    strategy.tvShowFolderTemplate, strategy.seasonFolderTemplate, strategy.episodeFileTemplate, case.media, case.parsed, Path(case.source),
                )
                add("${strategy.name} | ${path.segments()}")
            }
        }
    }

    @Test
    fun `the built-in templates name every case as before`() {
        assertEquals(EXPECTED.trimIndent(), render().joinToString("\n"))
    }

    private companion object {
        const val EXPECTED = """
            Plex | Inception (2010) {imdb-tt1375666}/Inception (2010).mkv
            Plex | Inception (2010) {imdb-tt1375666}/Inception (2010) - [1080p] [BluRay] [YTS].mkv
            Plex | Inception {imdb-tt1375666}/Inception.mkv
            Plex | Inception (2010)/Inception (2010).mkv
            Plex | Inception (2010) {imdb-tt1375666}/Inception (2010).mkv
            Plex | Inception/Inception.mkv
            Plex | Inception (2010) {imdb-tt1375666}/Inception (2010)
            Plex | Blade Runner (1982) {imdb-tt1375666}/Blade Runner (1982) - [2160p] [BluRay] [HDR10] [Final Cut] [FraMeSToR].mkv
            Plex | Inception (2010) {imdb-tt1375666}/Inception (2010) - [1080p].mkv
            Plex | Inception (2010) {imdb-tt1375666}/Inception (2010) - [Group A-B].mkv
            Plex | Avatar The Way of Water (2022) {imdb-tt1375666}/Avatar The Way of Water (2022).mkv
            Plex | MASH Pilot Part 1-2 (1972) {imdb-tt1375666}/MASH Pilot Part 1-2 (1972).mkv
            Plex | Fate-Zero (2011) {imdb-tt1375666}/Fate-Zero (2011).mkv
            Plex | .hack-SIGN (2002) {imdb-tt1375666}/.hack-SIGN (2002).mkv
            Plex | What If... (1999) {imdb-tt1375666}/What If... (1999).mkv
            Plex | Re ZERO Starting Life (2016) {imdb-tt1375666}/Re ZERO Starting Life (2016).mkv
            Plex | tmdb-27205 (2010) {imdb-tt1375666}/tmdb-27205 (2010).mkv
            Plex | A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie Title That… (2010) {imdb-tt1375666}/A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie… (2010) - [1080p] [BluRay] [YTS].mkv
            Plex | とても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトルとても長… (2020) {imdb-tt1375666}/とても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトル… (2020) - [1080p] [BluRay] [YTS].mkv
            Plex | 😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀… (2020) {imdb-tt1375666}/😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀… (2020).mkv
            Plex | Inception (2010) {imdb-tt1375666}/… (2010) - [EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE.mkv
            Plex | Inception (2010) {imdb-tt1375666}/Inc… (2010) - [EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE].mkv
            Plex | [REC] (2007) {imdb-tt1375666}/[REC] (2007).mkv
            Plex | The Boys (2019) {imdb-tt1190634}/Season 03/The Boys (2019) - S03E01 - Payback.mkv
            Plex | The Boys (2019) {imdb-tt1190634}/Season 03/The Boys (2019) - S03E01 - Payback - [720p] [WEB-DL] [NTb].mkv
            Plex | The Boys (2019) {imdb-tt1190634}/Season 03/The Boys (2019) - S03E01-E02 - Payback & The Only Man in the Sky - [720p] [WEB-DL] [NTb].mkv
            Plex | The Boys {imdb-tt1190634}/Season 03/The Boys - S03E01 - Payback.mkv
            Plex | The Boys (2019)/Season 03/The Boys (2019) - S03E01 - Payback.mkv
            Plex | The Boys (2019) {imdb-tt1190634}/Season 03/The Boys (2019) - S03E01 - Payback.mkv
            Plex | The Boys/Season 03/The Boys - S03E01 - Payback.mkv
            Plex | The Boys (2019) {imdb-tt1190634}/Season 03/The Boys (2019) - S03E01 - Who's Afraid Of-The Big Bad Wolf.mkv
            Plex | The Boys (2019) {imdb-tt1190634}/Season 03/The Boys (2019) - S03E01 - .mkv
            Plex | The Boys (2019) {imdb-tt1190634}/Season 00/The Boys (2019) - S00E112 - Special.mkv
            Plex | Marvel's Agents of S.H.I.E.L.D. (2013) {imdb-tt1190634}/Season 03/Marvel's Agents of S.H.I.E.L.D. (2013) - S03E01 - Pilot.mkv
            Plex | The Boys (2019) {imdb-tt1190634}/Season 01/The Boys (2019) - S01E01-E12 - The Rather Long Episode Title Number 1 & The Rather Long Episode Title Number 2 & The Rather Long Episode Title Number 3 & The Rather Long Episode Title Number 4 & The Rather Long Episode Title… - [720p] [WEB-DL] [NTb].mkv
            Plex | An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title… (2019) {imdb-tt1190634}/Season 01/An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An… (2019) - S01E01-E12 - … - [720p] [WEB-DL] [NTb].mkv
            Plex | An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title… (2019) {imdb-tt1190634}/Season 03/An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An… (2019) - S03E01 - … - [720p] [WEB-DL] [NTb].mkv
            Plex | An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title… (2019) {imdb-tt1190634}/Season 01/An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long… (2019) - S01E01-E112 - … - [720p] [WEB-DL] [Extended Directors Cut Remastered Anniversary Edition] [SomeVeryLongReleaseGroupName].mkv
            Jellyfin | Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205].mkv
            Jellyfin | Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205] - [1080p] [BluRay] [YTS].mkv
            Jellyfin | Inception [tmdbid-27205]/Inception [tmdbid-27205].mkv
            Jellyfin | Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205].mkv
            Jellyfin | Inception (2010)/Inception (2010) .mkv
            Jellyfin | Inception/Inception .mkv
            Jellyfin | Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205]
            Jellyfin | Blade Runner (1982) [tmdbid-27205]/Blade Runner (1982) [tmdbid-27205] - [2160p] [BluRay] [HDR10] [Final Cut] [FraMeSToR].mkv
            Jellyfin | Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205] - [1080p].mkv
            Jellyfin | Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205] - [Group A-B].mkv
            Jellyfin | Avatar The Way of Water (2022) [tmdbid-27205]/Avatar The Way of Water (2022) [tmdbid-27205].mkv
            Jellyfin | MASH Pilot Part 1-2 (1972) [tmdbid-27205]/MASH Pilot Part 1-2 (1972) [tmdbid-27205].mkv
            Jellyfin | Fate-Zero (2011) [tmdbid-27205]/Fate-Zero (2011) [tmdbid-27205].mkv
            Jellyfin | .hack-SIGN (2002) [tmdbid-27205]/.hack-SIGN (2002) [tmdbid-27205].mkv
            Jellyfin | What If... (1999) [tmdbid-27205]/What If... (1999) [tmdbid-27205].mkv
            Jellyfin | Re ZERO Starting Life (2016) [tmdbid-27205]/Re ZERO Starting Life (2016) [tmdbid-27205].mkv
            Jellyfin | tmdb-27205 (2010) [tmdbid-27205]/tmdb-27205 (2010) [tmdbid-27205].mkv
            Jellyfin | A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes… (2010) [tmdbid-27205]/A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A Rather Long Movie Title That Goes On A… (2010) [tmdbid-27205] - [1080p] [BluRay] [YTS].mkv
            Jellyfin | とても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトルとても長… (2020) [tmdbid-27205]/とても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトルとても長い映画のタイトルとても長い映画… (2020) [tmdbid-27205] - [1080p] [BluRay] [YTS].mkv
            Jellyfin | 😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀… (2020) [tmdbid-27205]/😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀… (2020) [tmdbid-27205].mkv
            Jellyfin | Inception (2010) [tmdbid-27205]/… (2010) [tmdbid-27205] - [EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE.mkv
            Jellyfin | Inception (2010) [tmdbid-27205]/… (2010) [tmdbid-27205] - [EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE.mkv
            Jellyfin | [REC] (2007) [tmdbid-27205]/[REC] (2007) [tmdbid-27205].mkv
            Jellyfin | The Boys (2019) [tmdbid-76479]/Season 03/The Boys (2019) - S03E01 - Payback.mkv
            Jellyfin | The Boys (2019) [tmdbid-76479]/Season 03/The Boys (2019) - S03E01 - Payback - [720p] [WEB-DL] [NTb].mkv
            Jellyfin | The Boys (2019) [tmdbid-76479]/Season 03/The Boys (2019) - S03E01-E02 - Payback & The Only Man in the Sky - [720p] [WEB-DL] [NTb].mkv
            Jellyfin | The Boys [tmdbid-76479]/Season 03/The Boys - S03E01 - Payback.mkv
            Jellyfin | The Boys (2019) [tmdbid-76479]/Season 03/The Boys (2019) - S03E01 - Payback.mkv
            Jellyfin | The Boys (2019)/Season 03/The Boys (2019) - S03E01 - Payback.mkv
            Jellyfin | The Boys/Season 03/The Boys - S03E01 - Payback.mkv
            Jellyfin | The Boys (2019) [tmdbid-76479]/Season 03/The Boys (2019) - S03E01 - Who's Afraid Of-The Big Bad Wolf.mkv
            Jellyfin | The Boys (2019) [tmdbid-76479]/Season 03/The Boys (2019) - S03E01 - .mkv
            Jellyfin | The Boys (2019) [tmdbid-76479]/Season 00/The Boys (2019) - S00E112 - Special.mkv
            Jellyfin | Marvel's Agents of S.H.I.E.L.D. (2013) [tmdbid-76479]/Season 03/Marvel's Agents of S.H.I.E.L.D. (2013) - S03E01 - Pilot.mkv
            Jellyfin | The Boys (2019) [tmdbid-76479]/Season 01/The Boys (2019) - S01E01-E12 - The Rather Long Episode Title Number 1 & The Rather Long Episode Title Number 2 & The Rather Long Episode Title Number 3 & The Rather Long Episode Title Number 4 & The Rather Long Episode Title… - [720p] [WEB-DL] [NTb].mkv
            Jellyfin | An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That… (2019) [tmdbid-76479]/Season 01/An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An… (2019) - S01E01-E12 - … - [720p] [WEB-DL] [NTb].mkv
            Jellyfin | An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That… (2019) [tmdbid-76479]/Season 03/An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An… (2019) - S03E01 - … - [720p] [WEB-DL] [NTb].mkv
            Jellyfin | An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That… (2019) [tmdbid-76479]/Season 01/An Extremely Long Show Title That Keeps On Going An Extremely Long Show Title That Keeps On Going An Extremely Long… (2019) - S01E01-E112 - … - [720p] [WEB-DL] [Extended Directors Cut Remastered Anniversary Edition] [SomeVeryLongReleaseGroupName].mkv
        """
    }
}
