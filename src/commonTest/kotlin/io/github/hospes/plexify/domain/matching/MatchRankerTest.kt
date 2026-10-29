package io.github.hospes.plexify.domain.matching

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchRankerTest {

    private val ranker = MatchRanker()

    /** The best match, consolidated across providers; null when none qualifies. */
    private fun MatchRanker.bestMatch(
        results: List<MediaSearchResult>,
        parsedTitle: String,
        parsedYear: String?,
    ): CanonicalMedia? = rank(results, parsedTitle, parsedYear).matches.firstOrNull()?.media

    private fun kingdomsOfRuin(
        originalTitle: String? = null,
        alternativeTitles: List<String> = emptyList(),
    ) = MediaSearchResult.TvShow(
        title = "The Kingdoms of Ruin",
        year = "2023",
        tmdbId = "219673",
        provider = "TMDb",
        originalTitle = originalTitle,
        alternativeTitles = alternativeTitles,
    )

    @Test
    fun `matches show searched by alternative title`() {
        val results = listOf(
            kingdomsOfRuin(
                originalTitle = "破滅の王国",
                alternativeTitles = listOf("Hametsu no Oukoku"),
            )
        )

        val match = ranker.bestMatch(results, "hametsu no oukoku", null)

        assertIs<CanonicalMedia.TvShow>(match)
        assertEquals("The Kingdoms of Ruin", match.title)
        assertEquals("219673", match.tmdbId)
    }

    @Test
    fun `matches show searched by canonical title`() {
        val match = ranker.bestMatch(listOf(kingdomsOfRuin()), "the kingdoms of ruin", "2023")

        assertIs<CanonicalMedia.TvShow>(match)
        assertEquals("The Kingdoms of Ruin", match.title)
    }

    @Test
    fun `rejects show when no known title resembles the query`() {
        val match = ranker.bestMatch(listOf(kingdomsOfRuin()), "hametsu no oukoku", null)

        assertNull(match)
    }

    @Test
    fun `year override discards candidates with a different year`() {
        val results = listOf(kingdomsOfRuin(alternativeTitles = listOf("Hametsu no Oukoku")))

        val match = MatchRanker(yearOverride = "1995").bestMatch(results, "hametsu no oukoku", "1995")

        assertNull(match)
    }

    @Test
    fun `year override keeps candidates with the matching year`() {
        val results = listOf(kingdomsOfRuin(alternativeTitles = listOf("Hametsu no Oukoku")))

        val match = MatchRanker(yearOverride = "2023").bestMatch(results, "hametsu no oukoku", "2023")

        assertIs<CanonicalMedia.TvShow>(match)
        assertEquals("The Kingdoms of Ruin", match.title)
    }

    // TMDB results for "The Boys" as seen live: the spin-off only resembles the query through
    // its "The Boys: Diabolical" alias, but premiered in 2022, the year of the main show's S03.
    private val theBoysResults = listOf(
        MediaSearchResult.TvShow(title = "The Boys", year = "2019", tmdbId = "76479", provider = "TMDb", matchConfidence = 100.0),
        MediaSearchResult.TvShow(
            title = "The Boys Presents: Diabolical",
            year = "2022",
            tmdbId = "152483",
            provider = "TMDb",
            matchConfidence = 40.0,
            alternativeTitles = listOf("The Boys: Diabolical", "Diabolical"),
        ),
    )

    @Test
    fun `untranslated titles of the same year stay separate candidates`() {
        // TMDB has no English title for either, so neither has a Latin letter. The romaji query
        // matches only the second show's alias.
        val results = listOf(
            MediaSearchResult.TvShow(title = "破滅の王国", year = "2023", tmdbId = "1", provider = "TMDb", matchConfidence = 0.0),
            MediaSearchResult.TvShow(
                title = "滅びの都",
                year = "2023",
                tmdbId = "2",
                provider = "TMDb",
                matchConfidence = 100.0,
                alternativeTitles = listOf("Horobi no Miyako"),
            ),
        )

        val match = ranker.bestMatch(results, "Horobi no Miyako", "2023")

        assertIs<CanonicalMedia.TvShow>(match)
        assertEquals("2", match.tmdbId)
        assertEquals("滅びの都", match.title)
    }

    @Test
    fun `titles differing only in punctuation stay separate candidates`() {
        // TMDB's live results for "Hack": the anime .hack//SIGN is listed first, under ".hack", and
        // premiered the same year as CBS's Hack.
        val results = listOf(
            MediaSearchResult.TvShow(title = ".hack", year = "2002", tmdbId = "8864", provider = "TMDb", matchConfidence = 80.0),
            MediaSearchResult.TvShow(title = "Hack", year = "2002", tmdbId = "10457", provider = "TMDb", matchConfidence = 100.0),
        )

        val ranked = ranker.rank(results, "Hack", "2002").matches

        assertEquals(listOf("10457", "8864"), ranked.map { (it.media as CanonicalMedia.TvShow).tmdbId })
        assertEquals(listOf("Hack", ".hack"), ranked.map { (it.media as CanonicalMedia.TvShow).title })
    }

    @Test
    fun `a movie and a show of the same title and year stay separate candidates`() {
        val results = listOf(
            MediaSearchResult.TvShow(title = "Hack", year = "2002", tmdbId = "10457", provider = "TMDb", matchConfidence = 100.0),
            MediaSearchResult.Movie(title = "Hack", year = "2002", tmdbId = "10457", provider = "TMDb", matchConfidence = 100.0),
        )

        val ranked = ranker.rank(results, "Hack", "2002").matches

        assertEquals(listOf("TvShow", "Movie"), ranked.map { it.media::class.simpleName })
    }

    @Test
    fun `results of one title from two providers are one candidate`() {
        val results = listOf(
            MediaSearchResult.TvShow(title = "Hack", year = "2002", tmdbId = "10457", provider = "TMDb", matchConfidence = 100.0),
            MediaSearchResult.TvShow(title = "Hack", year = "2002", imdbId = "tt0320000", tmdbId = "10457", provider = "Other", matchConfidence = 100.0),
        )

        val ranked = ranker.rank(results, "Hack", "2002").matches

        val show = assertIs<CanonicalMedia.TvShow>(ranked.single().media)
        assertEquals("10457", show.tmdbId)
        assertEquals("tt0320000", show.imdbId)
    }

    @Test
    fun `episode year after the show premiere still matches the show`() {
        val match = ranker.bestMatch(theBoysResults, "The Boys", "2022")

        assertIs<CanonicalMedia.TvShow>(match)
        assertEquals("76479", match.tmdbId)
    }

    @Test
    fun `ranks every confident candidate best first`() {
        val unrelated = MediaSearchResult.TvShow(title = "Boyz", year = "2025", tmdbId = "9", provider = "TMDb", matchConfidence = 50.0)

        val ranked = ranker.rank(theBoysResults + unrelated, "The Boys", "2022").matches

        // "Boyz (2025)" passes the title floor but premiered after the filename year, which
        // rules it out.
        assertEquals(listOf("76479", "152483"), ranked.map { (it.media as CanonicalMedia.TvShow).tmdbId })
    }

    @Test
    fun `show premiering in the filename year beats an older show of the same title`() {
        val results = listOf(
            MediaSearchResult.TvShow(title = "Doctor Who", year = "1963", tmdbId = "121", provider = "TMDb", matchConfidence = 100.0),
            MediaSearchResult.TvShow(title = "Doctor Who", year = "2005", tmdbId = "57243", provider = "TMDb", matchConfidence = 100.0),
        )

        val match = ranker.bestMatch(results, "Doctor Who", "2005")

        assertIs<CanonicalMedia.TvShow>(match)
        assertEquals("57243", match.tmdbId)
    }

    @Test
    fun `show year scoring treats the filename year as on or after the premiere`() {
        assertEquals(10.0, yearScore(parsedYear = 2022, candidateYear = 2022, isShow = true))
        assertEquals(5.0, yearScore(parsedYear = 2022, candidateYear = 2021, isShow = true))
        assertEquals(4.8, yearScore(parsedYear = 2022, candidateYear = 2019, isShow = true), absoluteTolerance = 1e-9)
        assertEquals(1.0, yearScore(parsedYear = 2022, candidateYear = 1963, isShow = true))
        // Started a year later: the usual release-date disagreement, as for movies.
        assertEquals(5.0, yearScore(parsedYear = 2022, candidateYear = 2023, isShow = true))
        // A show cannot air an episode years before it premiered.
        assertEquals(-10.0, yearScore(parsedYear = 2022, candidateYear = 2025, isShow = true))
    }

    @Test
    fun `movie year scoring is unchanged`() {
        assertEquals(10.0, yearScore(parsedYear = 2021, candidateYear = 2021, isShow = false))
        assertEquals(5.0, yearScore(parsedYear = 2021, candidateYear = 2020, isShow = false))
        assertEquals(5.0, yearScore(parsedYear = 2021, candidateYear = 2022, isShow = false))
        assertEquals(-10.0, yearScore(parsedYear = 2021, candidateYear = 2016, isShow = false))
        assertEquals(-10.0, yearScore(parsedYear = 2021, candidateYear = 2026, isShow = false))
    }

    @Test
    fun `exact-title movie whose year is two or more off is rejected`() {
        // "Blade.Runner.2049.1080p.mkv" parses as year 2049; the 1982 film used to pass at exactly the minimum.
        val results = listOf(
            MediaSearchResult.Movie(title = "Blade Runner", year = "1982", tmdbId = "78", provider = "TMDb", matchConfidence = 100.0),
        )

        assertNull(ranker.bestMatch(results, "blade runner", "2049"))
        assertNull(ranker.bestMatch(results, "blade runner", "1980"))
    }

    @Test
    fun `exact-title movie one year off still matches`() {
        val results = listOf(
            MediaSearchResult.Movie(title = "Blade Runner", year = "1982", tmdbId = "78", provider = "TMDb", matchConfidence = 100.0),
        )

        val match = ranker.bestMatch(results, "blade runner", "1983")

        assertIs<CanonicalMedia.Movie>(match)
        assertEquals("78", match.tmdbId)
    }

    @Test
    fun `exact-title show premiering years after the filename year is rejected`() {
        val results = listOf(
            MediaSearchResult.TvShow(title = "The Boys", year = "2019", tmdbId = "76479", provider = "TMDb", matchConfidence = 100.0),
        )

        assertNull(ranker.bestMatch(results, "The Boys", "2016"))
    }

    @Test
    fun `match without a year keeps the year missing instead of zero`() {
        val results = listOf(
            MediaSearchResult.Movie(title = "Untitled Announced Film", year = null, tmdbId = "1", provider = "TMDb"),
            MediaSearchResult.TvShow(title = "Untitled Announced Show", year = null, tmdbId = "2", provider = "TMDb"),
        )

        val movie = ranker.bestMatch(results.take(1), "untitled announced film", null)
        val show = ranker.bestMatch(results.drop(1), "untitled announced show", null)

        assertIs<CanonicalMedia.Movie>(movie)
        assertNull(movie.year)
        assertIs<CanonicalMedia.TvShow>(show)
        assertNull(show.year)
    }

    @Test
    fun `a match has no miss reason`() {
        val ranking = ranker.rank(listOf(kingdomsOfRuin()), "the kingdoms of ruin", "2023")

        assertEquals(1, ranking.matches.size)
        assertNull(ranking.miss)
    }

    @Test
    fun `no results is no miss`() {
        val ranking = ranker.rank(emptyList(), "the kingdoms of ruin", "2023")

        assertEquals(emptyList(), ranking.matches)
        assertNull(ranking.miss)
        assertEquals(emptyList(), ranking.trace)
    }

    @Test
    fun `miss reason names the candidates the year override rejected`() {
        val ranking = MatchRanker(yearOverride = "1995").rank(listOf(kingdomsOfRuin()), "the kingdoms of ruin", "1995")

        assertEquals("'The Kingdoms of Ruin (2023)' rejected (year does not match override 1995)", ranking.miss)
        assertEquals("Candidate: 'The Kingdoms of Ruin (2023)' | Discarded (year does not match override 1995)", ranking.trace.last())
    }

    @Test
    fun `miss reason names the candidates the filename year rejected`() {
        val results = (1..5).map { MediaSearchResult.TvShow(title = "The Boys", year = "${2018 + it}", tmdbId = "$it", provider = "TMDb") }

        val ranking = ranker.rank(results, "The Boys", "2016")

        assertEquals(
            "'The Boys (2019)', 'The Boys (2020)', 'The Boys (2021)' and 2 more rejected (year does not match 2016)",
            ranking.miss,
        )
    }

    @Test
    fun `miss reason gives the best score under the minimum`() {
        // No year in the filename, so the candidate's year adds nothing: 4.0 + 10/20.
        val results = listOf(MediaSearchResult.Movie("Abxyz", "2001", tmdbId = "1", provider = "TMDb", matchConfidence = 10.0))

        val ranking = ranker.rank(results, "Abcde", null)

        assertEquals("best candidate 'Abxyz (2001)' scored 4.50, below the 5.0 confidence minimum", ranking.miss)
    }

    @Test
    fun `miss reason counts the results when no title resembles`() {
        val ranking = ranker.rank(listOf(kingdomsOfRuin()), "hametsu no oukoku", null)

        assertEquals("none of 1 provider result(s) resembled the title", ranking.miss)
        assertEquals("Candidate: 'The Kingdoms of Ruin (2023)' | Discarded (title similarity too low)", ranking.trace.last())
    }

    private fun movie(title: String, year: String?, confidence: Double = 100.0) =
        MediaSearchResult.Movie(title, year, tmdbId = "$title $year", provider = "TMDb", matchConfidence = confidence)

    private fun show(title: String, year: String?, id: String, confidence: Double = 100.0) =
        MediaSearchResult.TvShow(title, year, tmdbId = id, provider = "TMDb", matchConfidence = confidence)

    private fun MatchRanker.aliasesCanChangeWinner(results: List<MediaSearchResult>, parsedTitle: String, parsedYear: String?) =
        aliasesCanChangeWinner(results, results.awaitingAliases(), parsedTitle, parsedYear)

    @Test
    fun `aliases can make a later remake beat an exact title from an earlier year`() {
        // Live TMDB data (October 2026): the 2024 series is "Spice and Wolf (2024)" only by an alias. The 2008 one
        // scores 10 + 3.5 (a show that started 16 years before the file's year) + 5 = 18.5, and the 2024 one could
        // reach 10 + 10 + 5 with its alias, so its alias is looked up and it wins.
        val results = listOf(
            show("Spice and Wolf", "2008", "26707"),
            show("Spice and Wolf: MERCHANT MEETS THE WISE WOLF", "2024", "229676", calculateTitleConfidence("spice and wolf", "Spice and Wolf: MERCHANT MEETS THE WISE WOLF")),
        )
        assertTrue(ranker.aliasesCanChangeWinner(results, "spice and wolf", "2024"))

        val withAlias = results.mapIndexed { index, result ->
            if (index == 0) result else (result as MediaSearchResult.TvShow).copy(alternativeTitles = listOf("Spice and Wolf (2024)"), matchConfidence = 75.0)
        }
        assertEquals("229676", (ranker.rank(withAlias, "spice and wolf", "2024").matches.first().media as CanonicalMedia.TvShow).tmdbId)
    }

    @Test
    fun `no alias can beat an exact title from the file's year`() {
        // "Inception" (2010) scores 25; the documentary from 2010 could reach 25 too, but comes later, and the earlier wins a tie.
        val results = listOf(movie("Inception", "2010"), movie("The Inception of Inception", "2010", confidence = 40.0))

        assertFalse(ranker.aliasesCanChangeWinner(results, "inception", "2010"))
        assertTrue(ranker.aliasesCanChangeWinner(results.reversed(), "inception", "2010"))
    }

    @Test
    fun `without a year an exact title keeps its place only ahead of the results that await aliases`() {
        val exact = movie("Dark Angel", null)
        val awaiting = movie("Black Seraph", null, confidence = 30.0)

        assertFalse(ranker.aliasesCanChangeWinner(listOf(exact, awaiting), "dark angel", null))
        assertTrue(ranker.aliasesCanChangeWinner(listOf(awaiting, exact), "dark angel", null))
    }

    @Test
    fun `a result whose year rules it out can't change the winner`() {
        // The 1931 Dracula awaits aliases, but its year contradicts the file's, or the user's.
        val results = listOf(movie("Dracula", "1974", confidence = 90.0), movie("Drácula", "1931", confidence = 40.0))

        assertFalse(ranker.aliasesCanChangeWinner(results, "dracula", "1974"))
        assertFalse(MatchRanker(yearOverride = "1974").aliasesCanChangeWinner(results, "dracula", null))
    }

    @Test
    fun `aliases are looked up for a winner that awaits them itself`() {
        // Below the alias threshold the winner gets a lookup itself, which may name its seasons.
        val results = listOf(movie("S.W.A.T.", "2003", confidence = MatchScoring.ALT_TITLES_CONFIDENCE_THRESHOLD - 10))

        assertTrue(ranker.aliasesCanChangeWinner(results, "SWAT", "2003"))
    }

    @Test
    fun `without a winner aliases are looked up when a result awaits them`() {
        // A romaji title: only the alias matches.
        assertTrue(ranker.aliasesCanChangeWinner(listOf(kingdomsOfRuin()), "hametsu no oukoku", null))
        // Nothing awaits aliases, so a lookup can't change the miss.
        assertFalse(ranker.aliasesCanChangeWinner(listOf(movie("Hametsu", "2001", confidence = 60.0)), "hametsu no oukoku", null))
    }

    @Test
    fun `only the results that would get a lookup count`() {
        // The fourth weak result gets no lookup (MAX_ALT_TITLES_LOOKUPS), so it can't take the winner's place, though it
        // could tie with it and comes first.
        val weak = (1..3).map { movie("Unrelated $it", "1990", confidence = 10.0) }
        val results = weak + movie("Black Seraph", "2000", confidence = 10.0) + movie("Dark Angel", "2000")

        assertFalse(ranker.aliasesCanChangeWinner(results, "dark angel", "2000"))
    }

    @Test
    fun `only the matches the caller accepts count`() {
        // A caller that takes only a film whose title has the year: "Dracula" is not one, so the exact title doesn't
        // keep "Count Dracula 1974" from winning by an alias.
        val results = listOf(movie("Dracula", null), movie("Count Dracula 1974", null, confidence = 40.0))
        val namesYear = { media: CanonicalMedia -> "1974" in (media as CanonicalMedia.Movie).title }

        assertFalse(ranker.aliasesCanChangeWinner(results, results.awaitingAliases(), "dracula", null))
        assertTrue(ranker.aliasesCanChangeWinner(results, results.awaitingAliases(), "dracula", null, namesYear))
    }
}
