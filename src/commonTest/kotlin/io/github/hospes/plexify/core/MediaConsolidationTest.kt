package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.MetadataCache
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.domain.service.PathFormatter
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MediaConsolidationTest {

    private fun processor(yearOverride: String? = null) = MediaProcessor(
        metadataService = MetadataService(emptyList(), NamingStrategy.Jellyfin),
        fileOrganizer = DefaultFileOrganizer(PathFormatter(), NamingStrategy.Jellyfin),
        cache = MetadataCache(),
        yearOverride = yearOverride,
    )

    private val processor = processor()

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
    fun `matches show searched by alternative title`() = with(LoggingContext()) {
        val results = listOf(
            kingdomsOfRuin(
                originalTitle = "破滅の王国",
                alternativeTitles = listOf("Hametsu no Oukoku"),
            )
        )

        val match = processor.findAndConsolidateBestMatch(results, "hametsu no oukoku", null)

        assertIs<CanonicalMedia.TvShow>(match)
        assertEquals("The Kingdoms of Ruin", match.title)
        assertEquals("219673", match.tmdbId)
    }

    @Test
    fun `matches show searched by canonical title`() = with(LoggingContext()) {
        val match = processor.findAndConsolidateBestMatch(listOf(kingdomsOfRuin()), "the kingdoms of ruin", "2023")

        assertIs<CanonicalMedia.TvShow>(match)
        assertEquals("The Kingdoms of Ruin", match.title)
    }

    @Test
    fun `rejects show when no known title resembles the query`() = with(LoggingContext()) {
        val match = processor.findAndConsolidateBestMatch(listOf(kingdomsOfRuin()), "hametsu no oukoku", null)

        assertNull(match)
    }

    @Test
    fun `year override discards candidates with a different year`() = with(LoggingContext()) {
        val results = listOf(kingdomsOfRuin(alternativeTitles = listOf("Hametsu no Oukoku")))

        val match = processor(yearOverride = "1995").findAndConsolidateBestMatch(results, "hametsu no oukoku", "1995")

        assertNull(match)
    }

    @Test
    fun `year override keeps candidates with the matching year`() = with(LoggingContext()) {
        val results = listOf(kingdomsOfRuin(alternativeTitles = listOf("Hametsu no Oukoku")))

        val match = processor(yearOverride = "2023").findAndConsolidateBestMatch(results, "hametsu no oukoku", "2023")

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
    fun `episode year after the show premiere still matches the show`() = with(LoggingContext()) {
        val match = processor.findAndConsolidateBestMatch(theBoysResults, "The Boys", "2022")

        assertIs<CanonicalMedia.TvShow>(match)
        assertEquals("76479", match.tmdbId)
    }

    @Test
    fun `ranks every confident candidate best first`() = with(LoggingContext()) {
        val unrelated = MediaSearchResult.TvShow(title = "Boyz", year = "2025", tmdbId = "9", provider = "TMDb", matchConfidence = 50.0)

        val ranked = processor.rankMatches(theBoysResults + unrelated, "The Boys", "2022")

        // "Boyz (2025)" passes the title floor but premiered after the filename year, which
        // puts it below the confidence minimum.
        assertEquals(listOf("76479", "152483"), ranked.map { (it.media as CanonicalMedia.TvShow).tmdbId })
    }

    @Test
    fun `show premiering in the filename year beats an older show of the same title`() = with(LoggingContext()) {
        val results = listOf(
            MediaSearchResult.TvShow(title = "Doctor Who", year = "1963", tmdbId = "121", provider = "TMDb", matchConfidence = 100.0),
            MediaSearchResult.TvShow(title = "Doctor Who", year = "2005", tmdbId = "57243", provider = "TMDb", matchConfidence = 100.0),
        )

        val match = processor.findAndConsolidateBestMatch(results, "Doctor Who", "2005")

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
}
