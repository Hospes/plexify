package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.MediaSearchResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NumberedSeasonTest {

    private fun show(title: String, year: String, tmdbId: String, vararg alternativeTitles: String) =
        MediaSearchResult.TvShow(
            title = title,
            year = year,
            tmdbId = tmdbId,
            provider = "TMDb",
            matchConfidence = 100.0,
            alternativeTitles = alternativeTitles.toList(),
        )

    private val devilMayCry = show("Devil May Cry", "2025", "235930")
    private val recordOfRagnarok = show("Record of Ragnarok", "2021", "114868", "Shuumatsu no Walkure")

    private fun ProcessorRun.placeOf(fileName: String): Triple<String?, Int, Int>? =
        episodeOf(fileName)?.let { Triple(it.show.tmdbId, it.season, it.episode) }

    @Test
    fun `a number closing the title is the season when the show without it has that season`() = runTest {
        val provider = FakeTmdb(
            results = emptyList(),
            resultsByTitle = mapOf("devil may cry" to listOf(devilMayCry)),
            seasons = mapOf("235930" to mapOf(1 to 1..8, 2 to 1..8)),
        )

        val run = runProcessor(provider, "Devil_May_Cry_2_[01]_[HEVC].mkv", "Devil_May_Cry_2_[08]_[HEVC].mkv")

        assertEquals(Triple("235930", 2, 1), run.placeOf("Devil_May_Cry_2_[01]_[HEVC].mkv"))
        assertEquals(Triple("235930", 2, 8), run.placeOf("Devil_May_Cry_2_[08]_[HEVC].mkv"))
        // Decided once for the show: the second file searches nothing new.
        assertEquals(listOf("devil may cry", "devil may cry 2", "devil may cry"), provider.searchedTitles)
    }

    @Test
    fun `a roman numeral is the season even when the whole title matches the same show`() = runTest {
        val provider = FakeTmdb(
            results = listOf(recordOfRagnarok),
            seasons = mapOf("114868" to mapOf(1 to 1..12, 2 to 1..15, 3 to 1..15)),
        )

        val run = runProcessor(provider, "Shuumatsu_no_Walkure_III_[01]_[HEVC].mkv", "Shuumatsu_no_Walkure_III_[13]_[HEVC].mkv")

        assertEquals(Triple("114868", 3, 1), run.placeOf("Shuumatsu_no_Walkure_III_[01]_[HEVC].mkv"))
        assertEquals(Triple("114868", 3, 13), run.placeOf("Shuumatsu_no_Walkure_III_[13]_[HEVC].mkv"))
    }

    @Test
    fun `a number the show has no season for is part of the title`() = runTest {
        val kaiju = show("Kaiju No. 8", "2024", "207468")
        val provider = FakeTmdb(listOf(kaiju), seasons = mapOf("207468" to mapOf(1 to 1..12)))

        val run = runProcessor(provider, "Kaiju_No_8_[01]_[1080p].mkv")

        assertEquals(Triple("207468", 1, 1), run.placeOf("Kaiju_No_8_[01]_[1080p].mkv"))
    }

    @Test
    fun `a number is part of the title when the whole title is another show`() = runTest {
        val lupin = show("Lupin", "2021", "96677")
        val lupinTheThird = show("Lupin the 3rd", "1971", "31572", "Lupin III")
        val provider = FakeTmdb(
            results = emptyList(),
            resultsByTitle = mapOf("lupin" to listOf(lupin), "lupin iii" to listOf(lupinTheThird)),
            seasons = mapOf("96677" to mapOf(1 to 1..5, 3 to 1..7), "31572" to mapOf(1 to 1..23)),
        )

        val run = runProcessor(provider, "Lupin_III_[01]_[720p].mkv")

        assertEquals(Triple("31572", 1, 1), run.placeOf("Lupin_III_[01]_[720p].mkv"))
    }

    @Test
    fun `a season set on the command line agrees with the number while one that differs keeps it in the title`() = runTest {
        val provider = {
            FakeTmdb(
                results = emptyList(),
                resultsByTitle = mapOf("devil may cry" to listOf(devilMayCry)),
                seasons = mapOf("235930" to mapOf(1 to 1..8, 2 to 1..8)),
            )
        }

        val agrees = runProcessor(provider(), "Devil_May_Cry_2_[03]_[HEVC].mkv", seasonOverride = 2)
        val differs = runProcessor(provider(), "Devil_May_Cry_2_[03]_[HEVC].mkv", seasonOverride = 1)

        assertEquals(Triple("235930", 2, 3), agrees.placeOf("Devil_May_Cry_2_[03]_[HEVC].mkv"))
        assertEquals(null, differs.placeOf("Devil_May_Cry_2_[03]_[HEVC].mkv"))
        assertEquals(1, differs.stats.skipped)
    }
}
