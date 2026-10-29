package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.MetadataTimeoutException
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.service.PathFormatter
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.fail

/**
 * Golden transcripts: everything the processor prints for a scenario, concise and verbose, compared line for line, so a
 * change to the user-visible output shows up as a diff here. Each ends with the run's counts, as the CLI's `Done:` line
 * gives them. Files go through the real [DefaultFileOrganizer] in test mode, so the transcripts carry the library paths
 * (and its outcomes) the user sees. See [ProcessorRun.output] for how temporary paths are normalized.
 *
 * To update a transcript after an intended output change, run the test and open its report
 * (build/reports/tests/windowsTest/index.html, or linuxX64Test): the failure message gives each transcript that differs
 * as a ready-to-paste `concise = …` or `verbose = …` argument. Paste it over the old one, and check the diff. A refactor
 * that only moves lines between indent levels changes the verbose transcripts only: check the concise ones still pass
 * before pasting.
 */
class TranscriptTest {

    private val ghosts2019 = showResult("Ghosts", "2019", "1")
    private val ghosts2021 = showResult("Ghosts", "2021", "2", matchConfidence = 90.0)

    @Test
    fun `a movie found by search`() = transcripts(
        concise = """
            |Processing directory: <source> (1 media files)
            |  ✓ Inception.2010.1080p.BluRay.x264-SPARKS.mkv → Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205] - [1080p] [BluRay] [SPARKS].mkv
            |Done: 1 organized, 0 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <source> (1 media files)
            |  -> Processing: <source>/Inception.2010.1080p.BluRay.x264-SPARKS.mkv
            |    -> Parsed as Movie: Title='inception', Year='2010'
            |      -> Found 2 results from tmdb
            |      -> Consolidating 2 results for title: 'inception' year: '2010'
            |      -> 2 unique media candidates found.
            |      -> Candidate: 'Inception (2010)' | Score: 25.00
            |      -> Candidate: 'Inception: The Cobol Job (2010)' | Score: 17.50
            |      -> Best match selected: 'Inception' with score 25.00
            |    -> Found match: Movie(title=Inception, year=2010, imdbId=null, tmdbId=27205)
            |    -> ✓ Inception.2010.1080p.BluRay.x264-SPARKS.mkv → Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205] - [1080p] [BluRay] [SPARKS].mkv
            |    -> Organized at: <library>/Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205] - [1080p] [BluRay] [SPARKS].mkv
            |Done: 1 organized, 0 skipped, 0 failed.
        """,
    ) { verbose ->
        val provider = FakeTmdb(listOf(movieResult("Inception", "2010", "27205"), movieResult("Inception: The Cobol Job", "2010", "64956", matchConfidence = 60.0)))
        runProcessor(provider, "Inception.2010.1080p.BluRay.x264-SPARKS.mkv", verbose = verbose, organizer = organizer())
    }

    @Test
    fun `a show found by the ID in its folder`() = transcripts(
        concise = """
            |Processing directory: <source> (2 media files)
            |  Matched show by the ID in its path: .hack (2002) [tmdbid-8864]
            |  ✓ hack (2002) - S01E01 - Role Play.mkv → hack (2002) [tmdbid-8864]/Season 01/hack (2002) - S01E01 - Episode 1.mkv
            |  ✓ hack (2002) - S01E02 - Guardian.mkv → hack (2002) [tmdbid-8864]/Season 01/hack (2002) - S01E02 - Episode 2.mkv
            |Done: 2 organized, 0 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <source> (2 media files)
            |  -> Processing: <source>/hack (2002) [tmdbid-8864]/Season 01/hack (2002) - S01E01 - Role Play.mkv
            |    -> Parsed as TV Show: Show='hack', Season: 1, Episode: 1
            |      -> Looking up the show tmdb-8864 in the path
            |        -> Consolidating 1 results for title: 'hack' year: '2002'
            |        -> 1 unique media candidates found.
            |        -> Candidate: '.hack (2002)' | Score: 24.00
            |        -> Best match selected: '.hack' with score 24.00
            |      -> Matched show by the ID in its path: .hack (2002) [tmdbid-8864]
            |    -> Found show: TvShow(title=.hack, year=2002, imdbId=null, tmdbId=8864)
            |      -> Cache MISS for season: S1. Fetching from providers...
            |        -> Season 1 fetched from tmdb
            |    -> Found episode: S01E01 - Episode 1
            |    -> ✓ hack (2002) - S01E01 - Role Play.mkv → hack (2002) [tmdbid-8864]/Season 01/hack (2002) - S01E01 - Episode 1.mkv
            |    -> Organized at: <library>/hack (2002) [tmdbid-8864]/Season 01/hack (2002) - S01E01 - Episode 1.mkv
            |---
            |  -> Processing: <source>/hack (2002) [tmdbid-8864]/Season 01/hack (2002) - S01E02 - Guardian.mkv
            |    -> Parsed as TV Show: Show='hack', Season: 1, Episode: 2
            |    -> Found show: TvShow(title=.hack, year=2002, imdbId=null, tmdbId=8864)
            |      -> Cache HIT for S1 (28 episodes loaded)
            |    -> Found episode: S01E02 - Episode 2
            |    -> ✓ hack (2002) - S01E02 - Guardian.mkv → hack (2002) [tmdbid-8864]/Season 01/hack (2002) - S01E02 - Episode 2.mkv
            |    -> Organized at: <library>/hack (2002) [tmdbid-8864]/Season 01/hack (2002) - S01E02 - Episode 2.mkv
            |Done: 2 organized, 0 skipped, 0 failed.
        """,
    ) { verbose ->
        val provider = FakeTmdb(listOf(showResult(".hack", "2002", "8864", matchConfidence = 80.0), showResult("Hack", "2002", "10457")), seasons = mapOf("8864" to mapOf(1 to 1..28)))
        runProcessor(
            provider,
            "hack (2002) [tmdbid-8864]/Season 01/hack (2002) - S01E01 - Role Play.mkv",
            "hack (2002) [tmdbid-8864]/Season 01/hack (2002) - S01E02 - Guardian.mkv",
            verbose = verbose,
            organizer = organizer(),
        )
    }

    @Test
    fun `an ID in the path naming another movie gives way to the title`() = transcripts(
        concise = """
            |Processing directory: <source> (1 media files)
            |  Warning: the ID tmdb-10549 in the path is the movie 'Hamlet' (1996), not 'hamlet' (1990); matching by title
            |  ✓ Hamlet (1990) [tmdbid-10549].mkv → Hamlet (1990) [tmdbid-10264]/Hamlet (1990) [tmdbid-10264].mkv
            |Done: 1 organized, 0 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <source> (1 media files)
            |  -> Processing: <source>/Hamlet (1990) [tmdbid-10549]/Hamlet (1990) [tmdbid-10549].mkv
            |    -> Parsed as Movie: Title='hamlet', Year='1990'
            |      -> Looking up the movie tmdb-10549 in the path
            |        -> Consolidating 1 results for title: 'hamlet' year: '1990'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Hamlet (1996)' | Discarded (year does not match 1990)
            |        -> No match yet: 'Hamlet (1996)' rejected (year does not match 1990)
            |      -> Warning: the ID tmdb-10549 in the path is the movie 'Hamlet' (1996), not 'hamlet' (1990); matching by title
            |      -> Found 2 results from tmdb
            |      -> Consolidating 2 results for title: 'hamlet' year: '1990'
            |      -> 2 unique media candidates found.
            |      -> Candidate: 'Hamlet (1996)' | Discarded (year does not match 1990)
            |      -> Candidate: 'Hamlet (1990)' | Score: 25.00
            |      -> Best match selected: 'Hamlet' with score 25.00
            |    -> Found match: Movie(title=Hamlet, year=1990, imdbId=null, tmdbId=10264)
            |    -> ✓ Hamlet (1990) [tmdbid-10549].mkv → Hamlet (1990) [tmdbid-10264]/Hamlet (1990) [tmdbid-10264].mkv
            |    -> Organized at: <library>/Hamlet (1990) [tmdbid-10264]/Hamlet (1990) [tmdbid-10264].mkv
            |Done: 1 organized, 0 skipped, 0 failed.
        """,
    ) { verbose ->
        val provider = FakeTmdb(listOf(movieResult("Hamlet", "1996", "10549"), movieResult("Hamlet", "1990", "10264")))
        runProcessor(provider, "Hamlet (1990) [tmdbid-10549]/Hamlet (1990) [tmdbid-10549].mkv", verbose = verbose, organizer = organizer())
    }

    @Test
    fun `movies by the ID in their path or by a title that matches nothing confidently`() = transcripts(
        concise = """
            |Processing directory: <source> (4 media files)
            |  No movie has the ID tmdb-999 in the path; matching by title
            |  ✓ Arrival (2016) [tmdbid-999].mkv → Arrival (2016) [tmdbid-329865]/Arrival (2016) [tmdbid-329865].mkv
            |  Matched movie by the ID in its path: Dune: Part Two (2024)
            |  ✓ Dune Part Two (2024) [tmdbid-693134].mkv → Dune Part Two (2024) [tmdbid-693134]/Dune Part Two (2024) [tmdbid-693134].mkv
            |  No match for 'ponyo': none of 1 provider result(s) resembled the title
            |  ✗ Ponyo.1080p.mkv — no confident match for 'ponyo'
            |  No match for 'solaris': best candidate 'Solar Attack (2006)' scored 4.55, below the 5.0 confidence minimum
            |  ✗ Solaris.1080p.mkv — no confident match for 'solaris'
            |Done: 2 organized, 2 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <source> (4 media files)
            |  -> Processing: <source>/Arrival (2016) [tmdbid-999]/Arrival (2016) [tmdbid-999].mkv
            |    -> Parsed as Movie: Title='arrival', Year='2016'
            |      -> Looking up the movie tmdb-999 in the path
            |        -> tmdb: HTTP 404 looking up tmdb-999
            |      -> No movie has the ID tmdb-999 in the path; matching by title
            |      -> Found 1 results from tmdb
            |      -> Consolidating 1 results for title: 'arrival' year: '2016'
            |      -> 1 unique media candidates found.
            |      -> Candidate: 'Arrival (2016)' | Score: 25.00
            |      -> Best match selected: 'Arrival' with score 25.00
            |    -> Found match: Movie(title=Arrival, year=2016, imdbId=null, tmdbId=329865)
            |    -> ✓ Arrival (2016) [tmdbid-999].mkv → Arrival (2016) [tmdbid-329865]/Arrival (2016) [tmdbid-329865].mkv
            |    -> Organized at: <library>/Arrival (2016) [tmdbid-329865]/Arrival (2016) [tmdbid-329865].mkv
            |---
            |  -> Processing: <source>/Dune Part Two (2024) [tmdbid-693134]/Dune Part Two (2024) [tmdbid-693134].mkv
            |    -> Parsed as Movie: Title='dune part two', Year='2024'
            |      -> Looking up the movie tmdb-693134 in the path
            |        -> Consolidating 1 results for title: 'dune part two' year: '2024'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Dune: Part Two (2024)' | Score: 24.64
            |        -> Best match selected: 'Dune: Part Two' with score 24.64
            |      -> Matched movie by the ID in its path: Dune: Part Two (2024)
            |    -> Found match: Movie(title=Dune: Part Two, year=2024, imdbId=null, tmdbId=693134)
            |    -> ✓ Dune Part Two (2024) [tmdbid-693134].mkv → Dune Part Two (2024) [tmdbid-693134]/Dune Part Two (2024) [tmdbid-693134].mkv
            |    -> Organized at: <library>/Dune Part Two (2024) [tmdbid-693134]/Dune Part Two (2024) [tmdbid-693134].mkv
            |---
            |  -> Processing: <source>/Ponyo.1080p.mkv
            |    -> Parsed as Movie: Title='ponyo', Year='null'
            |      -> Found 1 results from tmdb
            |      -> Consolidating 1 results for title: 'ponyo' year: 'null'
            |      -> 1 unique media candidates found.
            |      -> Candidate: 'My Neighbor Totoro (1988)' | Discarded (title similarity too low)
            |      -> No match for 'ponyo': none of 1 provider result(s) resembled the title
            |    -> ✗ Ponyo.1080p.mkv — no confident match for 'ponyo'
            |---
            |  -> Processing: <source>/Solaris.1080p.mkv
            |    -> Parsed as Movie: Title='solaris', Year='null'
            |      -> Found 1 results from tmdb
            |      -> Alternative titles may change the match for 'solaris'; looking them up
            |      -> Consolidating 1 results for title: 'solaris' year: 'null'
            |      -> 1 unique media candidates found.
            |      -> Candidate: 'Solar Attack (2006)' | Score: 4.55
            |      -> No match for 'solaris': best candidate 'Solar Attack (2006)' scored 4.55, below the 5.0 confidence minimum
            |    -> ✗ Solaris.1080p.mkv — no confident match for 'solaris'
            |Done: 2 organized, 2 skipped, 0 failed.
        """,
    ) { verbose ->
        val provider = FakeTmdb(
            results = emptyList(),
            idRecords = listOf(movieResult("Dune: Part Two", "2024", "693134")),
            resultsByTitle = mapOf(
                "arrival" to listOf(movieResult("Arrival", "2016", "329865")),
                "solaris" to listOf(movieResult("Solar Attack", "2006", "1", matchConfidence = 0.0)),
                "ponyo" to listOf(movieResult("My Neighbor Totoro", "1988", "8392")),
            ),
        )
        runProcessor(
            provider,
            "Dune Part Two (2024) [tmdbid-693134]/Dune Part Two (2024) [tmdbid-693134].mkv",
            // TMDB has no movie 999: the ID gives way to the title.
            "Arrival (2016) [tmdbid-999]/Arrival (2016) [tmdbid-999].mkv",
            "Solaris.1080p.mkv",
            "Ponyo.1080p.mkv",
            verbose = verbose,
            organizer = organizer(),
        )
    }

    @Test
    fun `a runner-up show takes a season the best match lacks`() = transcripts(
        concise = """
            |Processing directory: <source> (2 media files)
            |  Matched show: Ghosts (2019) [tmdbid-1]
            |  Season 4 is not in Ghosts (2019) [tmdbid-1]; matched show: Ghosts (2021) [tmdbid-2]
            |  ✓ Ghosts.S04E01.1080p.WEB.mkv → Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1 - [1080p].mkv
            |  ✓ Ghosts.S04E02.1080p.WEB.mkv → Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E02 - Episode 2 - [1080p].mkv
            |Done: 2 organized, 0 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <source> (2 media files)
            |  -> Processing: <source>/Ghosts.S04E01.1080p.WEB.mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: 4, Episode: 1
            |      -> Cache MISS for show: 'ghosts'. Searching providers...
            |        -> Found 2 results from tmdb
            |        -> Consolidating 2 results for title: 'ghosts' year: 'null'
            |        -> 2 unique media candidates found.
            |        -> Candidate: 'Ghosts (2019)' | Score: 15.00
            |        -> Candidate: 'Ghosts (2021)' | Score: 14.50
            |        -> Best match selected: 'Ghosts' with score 15.00
            |      -> Matched show: Ghosts (2019) [tmdbid-1]
            |    -> Found show: TvShow(title=Ghosts, year=2019, imdbId=null, tmdbId=1)
            |      -> Cache MISS for season: S4. Fetching from providers...
            |        -> tmdb: HTTP 404 fetching season 4
            |      -> Episode E1 not found in S4 data. Trying episode groups...
            |        -> No episode group places S4E1 (0 group(s) checked).
            |      -> Cache MISS for season: S4. Fetching from providers...
            |        -> Season 4 fetched from tmdb
            |    -> Season 4 is not in Ghosts (2019) [tmdbid-1]; matched show: Ghosts (2021) [tmdbid-2]
            |    -> Found episode: S04E01 - Episode 1
            |    -> ✓ Ghosts.S04E01.1080p.WEB.mkv → Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1 - [1080p].mkv
            |    -> Organized at: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1 - [1080p].mkv
            |---
            |  -> Processing: <source>/Ghosts.S04E02.1080p.WEB.mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: 4, Episode: 2
            |      -> Cache HIT for show: 'ghosts'
            |    -> Found show: TvShow(title=Ghosts, year=2019, imdbId=null, tmdbId=1)
            |      -> Cache HIT for S4 (8 episodes loaded)
            |    -> Found episode: S04E02 - Episode 2
            |    -> ✓ Ghosts.S04E02.1080p.WEB.mkv → Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E02 - Episode 2 - [1080p].mkv
            |    -> Organized at: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E02 - Episode 2 - [1080p].mkv
            |Done: 2 organized, 0 skipped, 0 failed.
        """,
    ) { verbose ->
        val provider = FakeTmdb(listOf(ghosts2019, ghosts2021), seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..8)))
        runProcessor(provider, "Ghosts.S04E01.1080p.WEB.mkv", "Ghosts.S04E02.1080p.WEB.mkv", verbose = verbose, organizer = organizer())
    }

    @Test
    fun `an episode group maps a cour onto the provider's season`() = transcripts(
        concise = """
            |Processing directory: <source> (2 media files)
            |  Matched show: Gate (2015) [tmdbid-62745]
            |  Season 2 of 'Gate' mapped through episode group 'Cours' (S02E01 → S01E13)
            |  ✓ Gate - S02E01.mkv → Gate (2015) [tmdbid-62745]/Season 01/Gate (2015) - S01E13 - Episode 13.mkv
            |  ✓ Gate - S02E02.mkv → Gate (2015) [tmdbid-62745]/Season 01/Gate (2015) - S01E14 - Episode 14.mkv
            |Done: 2 organized, 0 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <source> (2 media files)
            |  -> Processing: <source>/Gate - S02E01.mkv
            |    -> Parsed as TV Show: Show='gate', Season: 2, Episode: 1
            |      -> Cache MISS for show: 'gate'. Searching providers...
            |        -> Found 1 results from tmdb
            |        -> Consolidating 1 results for title: 'gate' year: 'null'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Gate (2015)' | Score: 15.00
            |        -> Best match selected: 'Gate' with score 15.00
            |      -> Matched show: Gate (2015) [tmdbid-62745]
            |    -> Found show: TvShow(title=Gate, year=2015, imdbId=null, tmdbId=62745)
            |      -> Cache MISS for season: S2. Fetching from providers...
            |        -> tmdb: HTTP 404 fetching season 2
            |      -> Episode E1 not found in S2 data. Trying episode groups...
            |          -> 1 episode group(s) fetched from tmdb
            |        -> Season 2 of 'Gate' mapped through episode group 'Cours' (S02E01 → S01E13)
            |        -> Mapped S2E1 → S1E13 via 'Cours' part 'Cour 2'
            |    -> Found episode: S01E13 - Episode 13
            |    -> ✓ Gate - S02E01.mkv → Gate (2015) [tmdbid-62745]/Season 01/Gate (2015) - S01E13 - Episode 13.mkv
            |    -> Organized at: <library>/Gate (2015) [tmdbid-62745]/Season 01/Gate (2015) - S01E13 - Episode 13.mkv
            |---
            |  -> Processing: <source>/Gate - S02E02.mkv
            |    -> Parsed as TV Show: Show='gate', Season: 2, Episode: 2
            |      -> Cache HIT for show: 'gate'
            |    -> Found show: TvShow(title=Gate, year=2015, imdbId=null, tmdbId=62745)
            |      -> Episode E2 not found in S2 data. Trying episode groups...
            |        -> Mapped S2E2 → S1E14 via 'Cours' part 'Cour 2'
            |    -> Found episode: S01E14 - Episode 14
            |    -> ✓ Gate - S02E02.mkv → Gate (2015) [tmdbid-62745]/Season 01/Gate (2015) - S01E14 - Episode 14.mkv
            |    -> Organized at: <library>/Gate (2015) [tmdbid-62745]/Season 01/Gate (2015) - S01E14 - Episode 14.mkv
            |Done: 2 organized, 0 skipped, 0 failed.
        """,
    ) { verbose ->
        // The provider keeps one 24-episode season; its "Cours" group splits it in two, as the release does.
        val cours = { show: CanonicalMedia.TvShow ->
            val episodes = (1..24).map { CanonicalMedia.Episode(show, 1, it, "Episode $it") }
            listOf(
                CanonicalMedia.EpisodeGroup(
                    name = "Cours",
                    type = CanonicalMedia.EpisodeGroup.Type.TV,
                    parts = listOf(CanonicalMedia.EpisodeGroup.Part("Cour 1", 1, episodes.take(12)), CanonicalMedia.EpisodeGroup.Part("Cour 2", 2, episodes.drop(12))),
                )
            )
        }
        val provider = FakeTmdb(listOf(showResult("Gate", "2015", "62745")), seasons = mapOf("62745" to mapOf(1 to 1..24)), episodeGroupsOf = cours)
        runProcessor(provider, "Gate - S02E01.mkv", "Gate - S02E02.mkv", verbose = verbose, organizer = organizer())
    }

    @Test
    fun `an arc title names the season`() = transcripts(
        concise = """
            |Processing directory: <source> (3 media files)
            |  Matched show: Demon Slayer: Kimetsu no Yaiba (2019) [tmdbid-85937]
            |  No season number in filenames; 'Kimetsu no Yaiba: Hashira Geiko-hen' is Season 5 of Demon Slayer: Kimetsu no Yaiba (2019) [tmdbid-85937]
            |  ✓ Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[01]_[AniLibria]_[WEBRip_1080p_HEVC].mkv → Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 05/Demon Slayer Kimetsu no Yaiba (2019) - S05E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
            |  ✓ Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[08]_[AniLibria]_[WEBRip_1080p_HEVC].mkv → Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 05/Demon Slayer Kimetsu no Yaiba (2019) - S05E08 - Episode 8 - [1080p] [WEBRip] [AniLibria].mkv
            |  Matched show: Demon Slayer: Kimetsu no Yaiba (2019) [tmdbid-85937]
            |  No season number in filenames; 'Kimetsu no Yaiba: Yuukaku-hen' is Season 3 of Demon Slayer: Kimetsu no Yaiba (2019) [tmdbid-85937]
            |  ✓ Kimetsu_no_Yaiba_-_Yuukaku-hen_[01]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv → Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 03/Demon Slayer Kimetsu no Yaiba (2019) - S03E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
            |Done: 3 organized, 0 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <source> (3 media files)
            |  -> Processing: <source>/Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[01]_[AniLibria]_[WEBRip_1080p_HEVC].mkv
            |    -> Parsed as TV Show: Show='kimetsu no yaiba hashira geiko hen', Season: null, Episode: 1
            |      -> Cache MISS for show: 'kimetsu no yaiba hashira geiko hen'. Searching providers...
            |        -> Found 1 results from tmdb
            |        -> Consolidating 1 results for title: 'kimetsu no yaiba hashira geiko hen' year: 'null'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Demon Slayer: Kimetsu no Yaiba (2019)' | Score: 15.00 (matched via 'Kimetsu no Yaiba: Hashira Geiko-hen')
            |        -> Best match selected: 'Demon Slayer: Kimetsu no Yaiba' with score 15.00
            |      -> Matched show: Demon Slayer: Kimetsu no Yaiba (2019) [tmdbid-85937]
            |    -> Found show: TvShow(title=Demon Slayer: Kimetsu no Yaiba, year=2019, imdbId=null, tmdbId=85937)
            |    -> No season number in filenames; 'Kimetsu no Yaiba: Hashira Geiko-hen' is Season 5 of Demon Slayer: Kimetsu no Yaiba (2019) [tmdbid-85937]
            |      -> Cache MISS for season: S5. Fetching from providers...
            |        -> Season 5 fetched from tmdb
            |    -> Found episode: S05E01 - Episode 1
            |    -> ✓ Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[01]_[AniLibria]_[WEBRip_1080p_HEVC].mkv → Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 05/Demon Slayer Kimetsu no Yaiba (2019) - S05E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
            |    -> Organized at: <library>/Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 05/Demon Slayer Kimetsu no Yaiba (2019) - S05E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
            |---
            |  -> Processing: <source>/Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[08]_[AniLibria]_[WEBRip_1080p_HEVC].mkv
            |    -> Parsed as TV Show: Show='kimetsu no yaiba hashira geiko hen', Season: null, Episode: 8
            |      -> Cache HIT for show: 'kimetsu no yaiba hashira geiko hen'
            |    -> Found show: TvShow(title=Demon Slayer: Kimetsu no Yaiba, year=2019, imdbId=null, tmdbId=85937)
            |      -> Cache HIT for S5 (8 episodes loaded)
            |    -> Found episode: S05E08 - Episode 8
            |    -> ✓ Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[08]_[AniLibria]_[WEBRip_1080p_HEVC].mkv → Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 05/Demon Slayer Kimetsu no Yaiba (2019) - S05E08 - Episode 8 - [1080p] [WEBRip] [AniLibria].mkv
            |    -> Organized at: <library>/Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 05/Demon Slayer Kimetsu no Yaiba (2019) - S05E08 - Episode 8 - [1080p] [WEBRip] [AniLibria].mkv
            |---
            |  -> Processing: <source>/Kimetsu_no_Yaiba_-_Yuukaku-hen_[01]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
            |    -> Parsed as TV Show: Show='kimetsu no yaiba yuukaku hen', Season: null, Episode: 1
            |      -> Cache MISS for show: 'kimetsu no yaiba yuukaku hen'. Searching providers...
            |        -> Found 1 results from tmdb
            |        -> Consolidating 1 results for title: 'kimetsu no yaiba yuukaku hen' year: 'null'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Demon Slayer: Kimetsu no Yaiba (2019)' | Score: 15.00 (matched via 'Kimetsu no Yaiba: Yuukaku-hen')
            |        -> Best match selected: 'Demon Slayer: Kimetsu no Yaiba' with score 15.00
            |      -> Matched show: Demon Slayer: Kimetsu no Yaiba (2019) [tmdbid-85937]
            |    -> Found show: TvShow(title=Demon Slayer: Kimetsu no Yaiba, year=2019, imdbId=null, tmdbId=85937)
            |    -> No season number in filenames; 'Kimetsu no Yaiba: Yuukaku-hen' is Season 3 of Demon Slayer: Kimetsu no Yaiba (2019) [tmdbid-85937]
            |      -> Cache MISS for season: S3. Fetching from providers...
            |        -> Season 3 fetched from tmdb
            |    -> Found episode: S03E01 - Episode 1
            |    -> ✓ Kimetsu_no_Yaiba_-_Yuukaku-hen_[01]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv → Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 03/Demon Slayer Kimetsu no Yaiba (2019) - S03E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
            |    -> Organized at: <library>/Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 03/Demon Slayer Kimetsu no Yaiba (2019) - S03E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
            |Done: 3 organized, 0 skipped, 0 failed.
        """,
    ) { verbose ->
        runProcessor(
            FakeTmdb(listOf(demonSlayer), seasons = mapOf("85937" to mapOf(1 to 1..26, 3 to 1..11, 5 to 1..8))),
            "Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[01]_[AniLibria]_[WEBRip_1080p_HEVC].mkv",
            "Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[08]_[AniLibria]_[WEBRip_1080p_HEVC].mkv",
            "Kimetsu_no_Yaiba_-_Yuukaku-hen_[01]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv",
            verbose = verbose,
            organizer = organizer(),
        )
    }

    @Test
    fun `an arc no season title places is skipped`() = transcripts(
        concise = """
            |Processing directory: <source> (1 media files)
            |  Matched show: Demon Slayer: Kimetsu no Yaiba (2019) [tmdbid-85937]
            |  ✗ Kimetsu_no_Yaiba_-_Yuukaku-hen_[10]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv — 'kimetsu no yaiba yuukaku hen' names an arc that matches no season title of Demon Slayer: Kimetsu no Yaiba (2019) [tmdbid-85937]; set the season with -s/--season
            |Done: 0 organized, 1 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <source> (1 media files)
            |  -> Processing: <source>/Kimetsu_no_Yaiba_-_Yuukaku-hen_[10]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
            |    -> Parsed as TV Show: Show='kimetsu no yaiba yuukaku hen', Season: null, Episode: 10
            |      -> Cache MISS for show: 'kimetsu no yaiba yuukaku hen'. Searching providers...
            |        -> Found 1 results from tmdb
            |        -> Consolidating 1 results for title: 'kimetsu no yaiba yuukaku hen' year: 'null'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Demon Slayer: Kimetsu no Yaiba (2019)' | Score: 10.83 (matched via 'Kimetsu no Yaiba')
            |        -> Best match selected: 'Demon Slayer: Kimetsu no Yaiba' with score 10.83
            |      -> Matched show: Demon Slayer: Kimetsu no Yaiba (2019) [tmdbid-85937]
            |    -> Found show: TvShow(title=Demon Slayer: Kimetsu no Yaiba, year=2019, imdbId=null, tmdbId=85937)
            |    -> ✗ Kimetsu_no_Yaiba_-_Yuukaku-hen_[10]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv — 'kimetsu no yaiba yuukaku hen' names an arc that matches no season title of Demon Slayer: Kimetsu no Yaiba (2019) [tmdbid-85937]; set the season with -s/--season
            |Done: 0 organized, 1 skipped, 0 failed.
        """,
    ) { verbose ->
        val untagged = demonSlayer.copy(alternativeTitles = listOf("Kimetsu no Yaiba"), seasonTitles = emptyMap())
        runProcessor(
            FakeTmdb(listOf(untagged), seasons = mapOf("85937" to mapOf(1 to 1..26, 3 to 1..11))),
            "Kimetsu_no_Yaiba_-_Yuukaku-hen_[10]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv",
            verbose = verbose,
            organizer = organizer(),
        )
    }

    @Test
    fun `a number closing the title is the season`() = transcripts(
        concise = """
            |Processing directory: <source> (2 media files)
            |  No season number in filenames; 'devil may cry 2' is Season 2 of Devil May Cry (2025) [tmdbid-235930]
            |  Matched show: Devil May Cry (2025) [tmdbid-235930]
            |  ✓ Devil_May_Cry_2_[01]_[HEVC].mkv → Devil May Cry (2025) [tmdbid-235930]/Season 02/Devil May Cry (2025) - S02E01 - Episode 1.mkv
            |  ✓ Devil_May_Cry_2_[08]_[HEVC].mkv → Devil May Cry (2025) [tmdbid-235930]/Season 02/Devil May Cry (2025) - S02E08 - Episode 8.mkv
            |Done: 2 organized, 0 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <source> (2 media files)
            |  -> Processing: <source>/Devil_May_Cry_2_[01]_[HEVC].mkv
            |    -> Parsed as TV Show: Show='devil may cry 2', Season: null, Episode: 1
            |      -> Trying 'devil may cry' Season 2 for 'devil may cry 2'
            |        -> Found 1 results from tmdb
            |        -> Consolidating 1 results for title: 'devil may cry' year: 'null'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Devil May Cry (2025)' | Score: 15.00
            |        -> Best match selected: 'Devil May Cry' with score 15.00
            |      -> Cache MISS for season: S2. Fetching from providers...
            |        -> Season 2 fetched from tmdb
            |        -> Found 0 results from tmdb
            |    -> No season number in filenames; 'devil may cry 2' is Season 2 of Devil May Cry (2025) [tmdbid-235930]
            |      -> Cache MISS for show: 'devil may cry'. Searching providers...
            |        -> Found 1 results from tmdb
            |        -> Consolidating 1 results for title: 'devil may cry' year: 'null'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Devil May Cry (2025)' | Score: 15.00
            |        -> Best match selected: 'Devil May Cry' with score 15.00
            |      -> Matched show: Devil May Cry (2025) [tmdbid-235930]
            |    -> Found show: TvShow(title=Devil May Cry, year=2025, imdbId=null, tmdbId=235930)
            |      -> Cache HIT for S2 (8 episodes loaded)
            |    -> Found episode: S02E01 - Episode 1
            |    -> ✓ Devil_May_Cry_2_[01]_[HEVC].mkv → Devil May Cry (2025) [tmdbid-235930]/Season 02/Devil May Cry (2025) - S02E01 - Episode 1.mkv
            |    -> Organized at: <library>/Devil May Cry (2025) [tmdbid-235930]/Season 02/Devil May Cry (2025) - S02E01 - Episode 1.mkv
            |---
            |  -> Processing: <source>/Devil_May_Cry_2_[08]_[HEVC].mkv
            |    -> Parsed as TV Show: Show='devil may cry 2', Season: null, Episode: 8
            |      -> Cache HIT for show: 'devil may cry'
            |    -> Found show: TvShow(title=Devil May Cry, year=2025, imdbId=null, tmdbId=235930)
            |      -> Cache HIT for S2 (8 episodes loaded)
            |    -> Found episode: S02E08 - Episode 8
            |    -> ✓ Devil_May_Cry_2_[08]_[HEVC].mkv → Devil May Cry (2025) [tmdbid-235930]/Season 02/Devil May Cry (2025) - S02E08 - Episode 8.mkv
            |    -> Organized at: <library>/Devil May Cry (2025) [tmdbid-235930]/Season 02/Devil May Cry (2025) - S02E08 - Episode 8.mkv
            |Done: 2 organized, 0 skipped, 0 failed.
        """,
    ) { verbose ->
        val provider = FakeTmdb(
            results = emptyList(),
            resultsByTitle = mapOf("devil may cry" to listOf(showResult("Devil May Cry", "2025", "235930"))),
            seasons = mapOf("235930" to mapOf(1 to 1..8, 2 to 1..8)),
        )
        runProcessor(provider, "Devil_May_Cry_2_[01]_[HEVC].mkv", "Devil_May_Cry_2_[08]_[HEVC].mkv", verbose = verbose, organizer = organizer())
    }

    @Test
    fun `a film missing from the first results is found with the year`() = transcripts(
        concise = """
            |Processing directory: <source> (1 media files)
            |  ✓ Dracula.1974.1080p.BluRay.mkv → Dracula (1974) [tmdbid-86889]/Dracula (1974) [tmdbid-86889] - [1080p] [BluRay].mkv
            |Done: 1 organized, 0 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <source> (1 media files)
            |  -> Processing: <source>/Dracula.1974.1080p.BluRay.mkv
            |    -> Parsed as Movie: Title='dracula', Year='1974'
            |      -> Found 2 results from tmdb
            |      -> Consolidating 2 results for title: 'dracula' year: '1974'
            |      -> 2 unique media candidates found.
            |      -> Candidate: 'Dracula (1931)' | Discarded (year does not match 1974)
            |      -> Candidate: 'Dracula (2025)' | Discarded (year does not match 1974)
            |      -> No match yet: 'Dracula (1931)', 'Dracula (2025)' rejected (year does not match 1974)
            |    -> No match from 1974 among the first results; searching again with the year
            |      -> Found 2 results from tmdb
            |      -> Consolidating 4 results for title: 'dracula' year: '1974'
            |      -> 4 unique media candidates found.
            |      -> Candidate: 'Dracula (1931)' | Discarded (year does not match 1974)
            |      -> Candidate: 'Dracula (2025)' | Discarded (year does not match 1974)
            |      -> Candidate: 'Countess Dracula (1971)' | Discarded (year does not match 1974)
            |      -> Candidate: 'Dracula (1974)' | Score: 25.00
            |      -> Best match selected: 'Dracula' with score 25.00
            |    -> Found match: Movie(title=Dracula, year=1974, imdbId=null, tmdbId=86889)
            |    -> ✓ Dracula.1974.1080p.BluRay.mkv → Dracula (1974) [tmdbid-86889]/Dracula (1974) [tmdbid-86889] - [1080p] [BluRay].mkv
            |    -> Organized at: <library>/Dracula (1974) [tmdbid-86889]/Dracula (1974) [tmdbid-86889] - [1080p] [BluRay].mkv
            |Done: 1 organized, 0 skipped, 0 failed.
        """,
    ) { verbose ->
        val provider = FakeTmdb(
            listOf(movieResult("Dracula", "1931", "138"), movieResult("Dracula", "2025", "1246049")),
            moviesByYear = mapOf("1974" to listOf(movieResult("Countess Dracula", "1971", "39043", matchConfidence = 50.0), movieResult("Dracula", "1974", "86889"))),
        )
        runProcessor(provider, "Dracula.1974.1080p.BluRay.mkv", verbose = verbose, organizer = organizer())
    }

    @Test
    fun `a year closing a movie name is read as part of its title`() = transcripts(
        concise = """
            |Processing directory: <source> (1 media files)
            |  ✓ Wonder.Woman.1984.1080p.WEBRip.mkv → Wonder Woman 1984 (2020) [tmdbid-464052]/Wonder Woman 1984 (2020) [tmdbid-464052] - [1080p] [WEBRip].mkv
            |Done: 1 organized, 0 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <source> (1 media files)
            |  -> Processing: <source>/Wonder.Woman.1984.1080p.WEBRip.mkv
            |    -> Parsed as Movie: Title='wonder woman', Year='1984'
            |      -> Found 1 results from tmdb
            |      -> Consolidating 1 results for title: 'wonder woman' year: '1984'
            |      -> 1 unique media candidates found.
            |      -> Candidate: 'Wonder Woman (2017)' | Discarded (year does not match 1984)
            |      -> No match yet: 'Wonder Woman (2017)' rejected (year does not match 1984)
            |    -> No match from 1984 among the first results; searching again with the year
            |      -> Found 0 results from tmdb
            |      -> Consolidating 1 results for title: 'wonder woman' year: '1984'
            |      -> 1 unique media candidates found.
            |      -> Candidate: 'Wonder Woman (2017)' | Discarded (year does not match 1984)
            |      -> No match yet: 'Wonder Woman (2017)' rejected (year does not match 1984)
            |    -> Still no match from 1984; trying 'wonder woman 1984' as the whole title
            |      -> Found 1 results from tmdb
            |      -> Consolidating 1 results for title: 'wonder woman 1984' year: 'null'
            |      -> 1 unique media candidates found.
            |      -> Candidate: 'Wonder Woman 1984 (2020)' | Score: 15.00
            |      -> Best match selected: 'Wonder Woman 1984' with score 15.00
            |    -> 'wonder woman 1984' matches better (15.00)
            |    -> Found match: Movie(title=Wonder Woman 1984, year=2020, imdbId=null, tmdbId=464052)
            |    -> ✓ Wonder.Woman.1984.1080p.WEBRip.mkv → Wonder Woman 1984 (2020) [tmdbid-464052]/Wonder Woman 1984 (2020) [tmdbid-464052] - [1080p] [WEBRip].mkv
            |    -> Organized at: <library>/Wonder Woman 1984 (2020) [tmdbid-464052]/Wonder Woman 1984 (2020) [tmdbid-464052] - [1080p] [WEBRip].mkv
            |Done: 1 organized, 0 skipped, 0 failed.
        """,
    ) { verbose ->
        val provider = FakeTmdb(
            results = listOf(movieResult("Wonder Woman", "2017", "297762")),
            resultsByTitle = mapOf("wonder woman 1984" to listOf(movieResult("Wonder Woman 1984", "2020", "464052"))),
        )
        runProcessor(provider, "Wonder.Woman.1984.1080p.WEBRip.mkv", verbose = verbose, organizer = organizer())
    }

    @Test
    fun `a season that fails to load fails the file`() = transcripts(
        concise = """
            |Processing directory: <source> (2 media files)
            |  Matched show: Ghosts (2021) [tmdbid-2]
            |  ✗ Ghosts.S04E01.1080p.WEB.mkv — TMDB rate limit reached (HTTP 429)
            |  ✓ Ghosts.S04E02.1080p.WEB.mkv → Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E02 - Episode 2 - [1080p].mkv
            |Done: 1 organized, 0 skipped, 1 failed.
        """,
        verbose = """
            |Processing directory: <source> (2 media files)
            |  -> Processing: <source>/Ghosts.S04E01.1080p.WEB.mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: 4, Episode: 1
            |      -> Cache MISS for show: 'ghosts'. Searching providers...
            |        -> Found 1 results from tmdb
            |        -> Consolidating 1 results for title: 'ghosts' year: 'null'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Ghosts (2021)' | Score: 14.50
            |        -> Best match selected: 'Ghosts' with score 14.50
            |      -> Matched show: Ghosts (2021) [tmdbid-2]
            |    -> Found show: TvShow(title=Ghosts, year=2021, imdbId=null, tmdbId=2)
            |      -> Cache MISS for season: S4. Fetching from providers...
            |        -> tmdb: TMDB rate limit reached (HTTP 429)
            |    -> ✗ Ghosts.S04E01.1080p.WEB.mkv — TMDB rate limit reached (HTTP 429)
            |---
            |  -> Processing: <source>/Ghosts.S04E02.1080p.WEB.mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: 4, Episode: 2
            |      -> Cache HIT for show: 'ghosts'
            |    -> Found show: TvShow(title=Ghosts, year=2021, imdbId=null, tmdbId=2)
            |      -> Cache MISS for season: S4. Fetching from providers...
            |        -> Season 4 fetched from tmdb
            |    -> Found episode: S04E02 - Episode 2
            |    -> ✓ Ghosts.S04E02.1080p.WEB.mkv → Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E02 - Episode 2 - [1080p].mkv
            |    -> Organized at: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E02 - Episode 2 - [1080p].mkv
            |Done: 1 organized, 0 skipped, 1 failed.
        """,
    ) { verbose ->
        // The first fetch fails; the second file's fetch loads it.
        val provider = FakeTmdb(listOf(ghosts2021), seasons = mapOf("2" to mapOf(4 to 1..8)), failingSeasons = setOf("2:4"), seasonFailures = 1)
        runProcessor(provider, "Ghosts.S04E01.1080p.WEB.mkv", "Ghosts.S04E02.1080p.WEB.mkv", verbose = verbose, organizer = organizer())
    }

    @Test
    fun `an IMDb lookup that fails fails the file`() = transcripts(
        concise = """
            |Processing directory: <source> (2 media files)
            |  ✗ Ghosts.S04E01.1080p.WEB.mkv — HTTP 503 fetching external IDs of 2
            |  Matched show: Ghosts (2021) [tmdbid-2] {imdb-tt2}
            |  ✓ Ghosts.S04E02.1080p.WEB.mkv → Ghosts (2021) {imdb-tt2}/Season 04/Ghosts (2021) - S04E02 - Episode 2 - [1080p].mkv
            |Done: 1 organized, 0 skipped, 1 failed.
        """,
        verbose = """
            |Processing directory: <source> (2 media files)
            |  -> Processing: <source>/Ghosts.S04E01.1080p.WEB.mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: 4, Episode: 1
            |      -> Cache MISS for show: 'ghosts'. Searching providers...
            |        -> Found 1 results from tmdb
            |        -> Consolidating 1 results for title: 'ghosts' year: 'null'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Ghosts (2021)' | Score: 14.50
            |        -> Best match selected: 'Ghosts' with score 14.50
            |        -> tmdb: HTTP 503 fetching external IDs of 2
            |    -> ✗ Ghosts.S04E01.1080p.WEB.mkv — HTTP 503 fetching external IDs of 2
            |---
            |  -> Processing: <source>/Ghosts.S04E02.1080p.WEB.mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: 4, Episode: 2
            |      -> Cache MISS for show: 'ghosts'. Searching providers...
            |        -> Found 1 results from tmdb
            |        -> Consolidating 1 results for title: 'ghosts' year: 'null'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Ghosts (2021)' | Score: 14.50
            |        -> Best match selected: 'Ghosts' with score 14.50
            |        -> External IDs fetched from tmdb: imdb=tt2
            |      -> Matched show: Ghosts (2021) [tmdbid-2] {imdb-tt2}
            |    -> Found show: TvShow(title=Ghosts, year=2021, imdbId=tt2, tmdbId=2)
            |      -> Cache MISS for season: S4. Fetching from providers...
            |        -> Season 4 fetched from tmdb
            |    -> Found episode: S04E02 - Episode 2
            |    -> ✓ Ghosts.S04E02.1080p.WEB.mkv → Ghosts (2021) {imdb-tt2}/Season 04/Ghosts (2021) - S04E02 - Episode 2 - [1080p].mkv
            |    -> Organized at: <library>/Ghosts (2021) {imdb-tt2}/Season 04/Ghosts (2021) - S04E02 - Episode 2 - [1080p].mkv
            |Done: 1 organized, 0 skipped, 1 failed.
        """,
    ) { verbose ->
        // The Plex template tags folders with the IMDb ID. The first lookup fails; the second file's loads it.
        val provider = FakeTmdb(listOf(ghosts2021), seasons = mapOf("2" to mapOf(4 to 1..8)), failingExternalIds = setOf("2"), externalIdFailures = 1)
        runProcessor(
            provider,
            "Ghosts.S04E01.1080p.WEB.mkv",
            "Ghosts.S04E02.1080p.WEB.mkv",
            namingStrategy = NamingStrategy.Plex,
            verbose = verbose,
            organizer = organizer(NamingStrategy.Plex),
        )
    }

    @Test
    fun `a search that fails fails the file`() = transcripts(
        concise = """
            |Processing directory: <source> (1 media files)
            |  ✗ Dracula.1974.1080p.BluRay.mkv — TMDB request timed out searching movies
            |Done: 0 organized, 0 skipped, 1 failed.
        """,
        verbose = """
            |Processing directory: <source> (1 media files)
            |  -> Processing: <source>/Dracula.1974.1080p.BluRay.mkv
            |    -> Parsed as Movie: Title='dracula', Year='1974'
            |      -> Found 1 results from tmdb
            |      -> Consolidating 1 results for title: 'dracula' year: '1974'
            |      -> 1 unique media candidates found.
            |      -> Candidate: 'Dracula (1931)' | Discarded (year does not match 1974)
            |      -> No match yet: 'Dracula (1931)' rejected (year does not match 1974)
            |    -> No match from 1974 among the first results; searching again with the year
            |    -> ✗ Dracula.1974.1080p.BluRay.mkv — TMDB request timed out searching movies
            |Done: 0 organized, 0 skipped, 1 failed.
        """,
    ) { verbose ->
        // The first search finds no Dracula from 1974, and the search with the year times out.
        val provider = FakeTmdb(
            listOf(movieResult("Dracula", "1931", "138")),
            yearSearchFailure = MetadataTimeoutException("TMDB request timed out searching movies"),
        )
        runProcessor(provider, "Dracula.1974.1080p.BluRay.mkv", verbose = verbose, organizer = organizer())
    }

    @Test
    fun `a second file for a target taken in this run is skipped`() = transcripts(
        concise = """
            |Processing directory: <source> (2 media files)
            |  Matched show: Ghosts (2021) [tmdbid-2]
            |  ✓ Ghosts - S04E01.mkv → Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv
            |  ✗ Ghosts.S04E01.mkv — same target as Ghosts - S04E01.mkv earlier in this run: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv
            |Done: 1 organized, 1 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <source> (2 media files)
            |  -> Processing: <source>/Ghosts - S04E01.mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: 4, Episode: 1
            |      -> Cache MISS for show: 'ghosts'. Searching providers...
            |        -> Found 1 results from tmdb
            |        -> Consolidating 1 results for title: 'ghosts' year: 'null'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Ghosts (2021)' | Score: 14.50
            |        -> Best match selected: 'Ghosts' with score 14.50
            |      -> Matched show: Ghosts (2021) [tmdbid-2]
            |    -> Found show: TvShow(title=Ghosts, year=2021, imdbId=null, tmdbId=2)
            |      -> Cache MISS for season: S4. Fetching from providers...
            |        -> Season 4 fetched from tmdb
            |    -> Found episode: S04E01 - Episode 1
            |    -> ✓ Ghosts - S04E01.mkv → Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv
            |    -> Organized at: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv
            |---
            |  -> Processing: <source>/Ghosts.S04E01.mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: 4, Episode: 1
            |      -> Cache HIT for show: 'ghosts'
            |    -> Found show: TvShow(title=Ghosts, year=2021, imdbId=null, tmdbId=2)
            |      -> Cache HIT for S4 (8 episodes loaded)
            |    -> Found episode: S04E01 - Episode 1
            |    -> ✗ Ghosts.S04E01.mkv — same target as Ghosts - S04E01.mkv earlier in this run: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv
            |Done: 1 organized, 1 skipped, 0 failed.
        """,
    ) { verbose ->
        val provider = FakeTmdb(listOf(ghosts2021), seasons = mapOf("2" to mapOf(4 to 1..8)))
        runProcessor(provider, "Ghosts - S04E01.mkv", "Ghosts.S04E01.mkv", verbose = verbose, organizer = organizer())
    }

    @Test
    fun `a file whose target holds another file is skipped`() = transcripts(
        concise = """
            |Processing directory: <source> (2 media files)
            |  Matched show: Ghosts (2021) [tmdbid-2]
            |  ✗ Ghosts.S04E01.mkv — target already exists: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv
            |  Matched show by the ID in its path: Ghosts (2021) [tmdbid-2]
            |  = Ghosts (2021) - S04E01 - Episode 1.mkv — already in the library: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv
            |Done: 0 organized, 2 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <source> (2 media files)
            |  -> Processing: <source>/Ghosts.S04E01.mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: 4, Episode: 1
            |      -> Cache MISS for show: 'ghosts'. Searching providers...
            |        -> Found 1 results from tmdb
            |        -> Consolidating 1 results for title: 'ghosts' year: 'null'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Ghosts (2021)' | Score: 14.50
            |        -> Best match selected: 'Ghosts' with score 14.50
            |      -> Matched show: Ghosts (2021) [tmdbid-2]
            |    -> Found show: TvShow(title=Ghosts, year=2021, imdbId=null, tmdbId=2)
            |      -> Cache MISS for season: S4. Fetching from providers...
            |        -> Season 4 fetched from tmdb
            |    -> Found episode: S04E01 - Episode 1
            |    -> ✗ Ghosts.S04E01.mkv — target already exists: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv
            |---
            |  -> Processing: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: 4, Episode: 1
            |      -> Looking up the show tmdb-2 in the path
            |        -> Consolidating 1 results for title: 'ghosts' year: '2021'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Ghosts (2021)' | Score: 25.00
            |        -> Best match selected: 'Ghosts' with score 25.00
            |      -> Matched show by the ID in its path: Ghosts (2021) [tmdbid-2]
            |    -> Found show: TvShow(title=Ghosts, year=2021, imdbId=null, tmdbId=2)
            |      -> Cache HIT for S4 (8 episodes loaded)
            |    -> Found episode: S04E01 - Episode 1
            |    -> = Ghosts (2021) - S04E01 - Episode 1.mkv — already in the library: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv
            |Done: 0 organized, 2 skipped, 0 failed.
        """,
    ) { verbose ->
        // A run into an existing library: the library (under the source folder here) already has the episode.
        val provider = FakeTmdb(listOf(ghosts2021), seasons = mapOf("2" to mapOf(4 to 1..8)))
        runProcessor(
            provider,
            "Ghosts.S04E01.mkv",
            "library/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv",
            verbose = verbose,
            organizer = organizer(),
        )
    }

    @Test
    fun `files already in their place in the library are left there`() = transcripts(
        concise = """
            |Processing directory: <library> (2 media files)
            |  Matched show by the ID in its path: Ghosts (2021) [tmdbid-2]
            |  = Ghosts (2021) - S04E01 - Episode 1.mkv — already in the library: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv
            |  = Ghosts (2021) - S04E02 - Episode 2.mkv — already in the library: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E02 - Episode 2.mkv
            |Done: 0 organized, 2 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <library> (2 media files)
            |  -> Processing: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: 4, Episode: 1
            |      -> Looking up the show tmdb-2 in the path
            |        -> Consolidating 1 results for title: 'ghosts' year: '2021'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Ghosts (2021)' | Score: 25.00
            |        -> Best match selected: 'Ghosts' with score 25.00
            |      -> Matched show by the ID in its path: Ghosts (2021) [tmdbid-2]
            |    -> Found show: TvShow(title=Ghosts, year=2021, imdbId=null, tmdbId=2)
            |      -> Cache MISS for season: S4. Fetching from providers...
            |        -> Season 4 fetched from tmdb
            |    -> Found episode: S04E01 - Episode 1
            |    -> = Ghosts (2021) - S04E01 - Episode 1.mkv — already in the library: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv
            |---
            |  -> Processing: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E02 - Episode 2.mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: 4, Episode: 2
            |    -> Found show: TvShow(title=Ghosts, year=2021, imdbId=null, tmdbId=2)
            |      -> Cache HIT for S4 (8 episodes loaded)
            |    -> Found episode: S04E02 - Episode 2
            |    -> = Ghosts (2021) - S04E02 - Episode 2.mkv — already in the library: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E02 - Episode 2.mkv
            |Done: 0 organized, 2 skipped, 0 failed.
        """,
    ) { verbose ->
        // Plexify run over its own library: each file's target is the file itself.
        val provider = FakeTmdb(listOf(ghosts2021), seasons = mapOf("2" to mapOf(4 to 1..8)))
        runProcessor(
            provider,
            "Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E01 - Episode 1.mkv",
            "Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E02 - Episode 2.mkv",
            dirName = "library",
            verbose = verbose,
            organizer = organizer(),
        )
    }

    @Test
    fun `files with no match are skipped`() = transcripts(
        concise = """
            |Processing directory: <source> (5 media files)
            |  Matched show: Ghosts (2021) [tmdbid-2]
            |  Warning: Ghosts.S04E08-E09.1080p.WEB.mkv — S04E09 is not the episode after S04E08 in 'Ghosts'; filing it as S04E08 only.
            |  ✓ Ghosts.S04E08-E09.1080p.WEB.mkv → Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E08 - Episode 8 - [1080p].mkv
            |  ✗ Ghosts.S04E12.1080p.WEB.mkv — episode S4E12 not found
            |  Warning: No season number found in filenames, defaulting to Season 1 (use -s/--season to set it explicitly).
            |  ✓ Ghosts_[03]_[1080p].mkv → Ghosts (2021) [tmdbid-2]/Season 01/Ghosts (2021) - S01E03 - Episode 3 - [1080p].mkv
            |  No match for 'unknown show': providers returned no results.
            |  ✗ Unknown.Show.S01E01.mkv — no confident match for show 'unknown show'
            |  ✗ Xqzv.1080p.mkv — no metadata found for 'xqzv'
            |Done: 2 organized, 3 skipped, 0 failed.
        """,
        verbose = """
            |Processing directory: <source> (5 media files)
            |  -> Processing: <source>/Ghosts.S04E08-E09.1080p.WEB.mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: 4, Episode: 8
            |      -> Cache MISS for show: 'ghosts'. Searching providers...
            |        -> Found 1 results from tmdb
            |        -> Consolidating 1 results for title: 'ghosts' year: 'null'
            |        -> 1 unique media candidates found.
            |        -> Candidate: 'Ghosts (2021)' | Score: 14.50
            |        -> Best match selected: 'Ghosts' with score 14.50
            |      -> Matched show: Ghosts (2021) [tmdbid-2]
            |    -> Found show: TvShow(title=Ghosts, year=2021, imdbId=null, tmdbId=2)
            |      -> Cache MISS for season: S4. Fetching from providers...
            |        -> Season 4 fetched from tmdb
            |      -> Cache HIT for S4 (8 episodes loaded)
            |      -> Episode E9 not found in S4 data. Trying episode groups...
            |        -> No episode group places S4E9 (0 group(s) checked).
            |    -> Warning: Ghosts.S04E08-E09.1080p.WEB.mkv — S04E09 is not the episode after S04E08 in 'Ghosts'; filing it as S04E08 only.
            |    -> Found episode: S04E08 - Episode 8
            |    -> ✓ Ghosts.S04E08-E09.1080p.WEB.mkv → Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E08 - Episode 8 - [1080p].mkv
            |    -> Organized at: <library>/Ghosts (2021) [tmdbid-2]/Season 04/Ghosts (2021) - S04E08 - Episode 8 - [1080p].mkv
            |---
            |  -> Processing: <source>/Ghosts.S04E12.1080p.WEB.mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: 4, Episode: 12
            |      -> Cache HIT for show: 'ghosts'
            |    -> Found show: TvShow(title=Ghosts, year=2021, imdbId=null, tmdbId=2)
            |      -> Cache HIT for S4 (8 episodes loaded)
            |      -> Episode E12 not found in S4 data. Trying episode groups...
            |        -> No episode group places S4E12 (0 group(s) checked).
            |    -> ✗ Ghosts.S04E12.1080p.WEB.mkv — episode S4E12 not found
            |---
            |  -> Processing: <source>/Ghosts_[03]_[1080p].mkv
            |    -> Parsed as TV Show: Show='ghosts', Season: null, Episode: 3
            |      -> Cache HIT for show: 'ghosts'
            |    -> Found show: TvShow(title=Ghosts, year=2021, imdbId=null, tmdbId=2)
            |    -> Warning: No season number found in filenames, defaulting to Season 1 (use -s/--season to set it explicitly).
            |      -> Cache MISS for season: S1. Fetching from providers...
            |        -> Season 1 fetched from tmdb
            |    -> Found episode: S01E03 - Episode 3
            |    -> ✓ Ghosts_[03]_[1080p].mkv → Ghosts (2021) [tmdbid-2]/Season 01/Ghosts (2021) - S01E03 - Episode 3 - [1080p].mkv
            |    -> Organized at: <library>/Ghosts (2021) [tmdbid-2]/Season 01/Ghosts (2021) - S01E03 - Episode 3 - [1080p].mkv
            |---
            |  -> Processing: <source>/Unknown.Show.S01E01.mkv
            |    -> Parsed as TV Show: Show='unknown show', Season: 1, Episode: 1
            |      -> Cache MISS for show: 'unknown show'. Searching providers...
            |        -> Found 0 results from tmdb
            |      -> No match for 'unknown show': providers returned no results.
            |    -> ✗ Unknown.Show.S01E01.mkv — no confident match for show 'unknown show'
            |---
            |  -> Processing: <source>/Xqzv.1080p.mkv
            |    -> Parsed as Movie: Title='xqzv', Year='null'
            |      -> Found 0 results from tmdb
            |    -> ✗ Xqzv.1080p.mkv — no metadata found for 'xqzv'
            |Done: 2 organized, 3 skipped, 0 failed.
        """,
    ) { verbose ->
        val provider = FakeTmdb(
            listOf(ghosts2021),
            resultsByTitle = mapOf("xqzv" to emptyList(), "unknown show" to emptyList()),
            seasons = mapOf("2" to mapOf(1 to 1..6, 4 to 1..8)),
        )
        runProcessor(
            provider,
            "Xqzv.1080p.mkv",
            "Unknown.Show.S01E01.mkv",
            "Ghosts.S04E12.1080p.WEB.mkv",
            "Ghosts_[03]_[1080p].mkv",
            "Ghosts.S04E08-E09.1080p.WEB.mkv",
            "notes.txt",
            verbose = verbose,
            organizer = organizer(),
        )
    }

    // Demon Slayer as TMDB has it: each arc its own season, the arcs' romaji titles tagged with their season.
    private val demonSlayer = showResult(
        title = "Demon Slayer: Kimetsu no Yaiba",
        year = "2019",
        tmdbId = "85937",
        alternativeTitles = listOf("Kimetsu no Yaiba", "Kimetsu no Yaiba: Yuukaku-hen", "Kimetsu no Yaiba: Hashira Geiko-hen"),
        seasonTitles = mapOf("Kimetsu no Yaiba: Yuukaku-hen" to 3, "Kimetsu no Yaiba: Hashira Geiko-hen" to 5),
    )

    private fun organizer(namingStrategy: NamingStrategy = NamingStrategy.Jellyfin) =
        RecordingOrganizer(DefaultFileOrganizer(PathFormatter(), namingStrategy))

    /** Runs [scenario] concise and verbose, each with fresh state, and compares each output with its transcript. */
    private fun transcripts(concise: String, verbose: String, scenario: suspend (verbose: Boolean) -> ProcessorRun<*>) = runTest {
        val mismatches = listOf("concise" to concise, "verbose" to verbose).mapNotNull { (name, expected) ->
            val run = scenario(name == "verbose")
            val actual = run.output + "Done: ${run.stats.organized} organized, ${run.stats.skipped} skipped, ${run.stats.failed} failed."
            if (expected.trimMargin().replace("\r", "").lines() == actual) null else name to actual
        }
        if (mismatches.isNotEmpty()) fail(mismatches.joinToString("\n", "Transcripts differ. Actual, to paste over the expected:\n") { (name, actual) -> pasteable(name, actual) })
    }

    /** [lines] as the `name = """…""",` argument of [transcripts], indented as in this file. */
    private fun pasteable(name: String, lines: List<String>): String = buildString {
        appendLine("        $name = \"\"\"")
        lines.forEach { appendLine("            |" + it.replace("$", "\${'$'}")) }
        append("        \"\"\",")
    }
}
