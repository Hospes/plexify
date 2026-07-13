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
}
