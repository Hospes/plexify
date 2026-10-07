package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.MediaSearchResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeasonFromTitleTest {

    // Demon Slayer as TMDB has it: one show, each arc its own season, the arcs' romaji titles
    // among the alternative titles tagged with their season.
    private val demonSlayer = MediaSearchResult.TvShow(
        title = "Demon Slayer: Kimetsu no Yaiba",
        year = "2019",
        tmdbId = "85937",
        provider = "TMDb",
        matchConfidence = 100.0,
        originalTitle = "鬼滅の刃",
        alternativeTitles = listOf(
            "Kimetsu no Yaiba",
            "Kimetsu no Yaiba: Mugen Ressha-hen",
            "Kimetsu no Yaiba: Yuukaku-hen",
            "Kimetsu no Yaiba: Hashira Geiko-hen",
        ),
        seasonTitles = mapOf(
            "Kimetsu no Yaiba: Mugen Ressha-hen" to 2,
            "Kimetsu no Yaiba: Yuukaku-hen" to 3,
            "Kimetsu no Yaiba: Hashira Geiko-hen" to 5,
        ),
    )

    private fun provider() = FakeTmdb(
        listOf(demonSlayer),
        seasons = mapOf("85937" to mapOf(1 to 1..26, 2 to 1..7, 3 to 1..11, 5 to 1..8)),
    )

    private fun ProcessorRun.placeOf(fileName: String): Pair<Int, Int>? = episodeOf(fileName)?.let { it.season to it.episode }

    @Test
    fun `an arc release without season numbers goes to the season its title names`() = runTest {
        val run = runProcessor(
            provider(),
            "Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[01]_[AniLibria]_[WEBRip_1080p_HEVC].mkv",
            "Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[08]_[AniLibria]_[WEBRip_1080p_HEVC].mkv",
            "Kimetsu_no_Yaiba_-_Yuukaku-hen_[01]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv",
            "Kimetsu_no_Yaiba_Mugen_Ressha-hen_(TV)_[07]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv",
        )

        assertEquals(5 to 1, run.placeOf("Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[01]_[AniLibria]_[WEBRip_1080p_HEVC].mkv"))
        assertEquals(5 to 8, run.placeOf("Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[08]_[AniLibria]_[WEBRip_1080p_HEVC].mkv"))
        assertEquals(3 to 1, run.placeOf("Kimetsu_no_Yaiba_-_Yuukaku-hen_[01]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv"))
        assertEquals(2 to 7, run.placeOf("Kimetsu_no_Yaiba_Mugen_Ressha-hen_(TV)_[07]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv"))
    }

    @Test
    fun `a release named after the whole show stays in season 1`() = runTest {
        val run = runProcessor(provider(), "Kimetsu_no_Yaiba_[01]_[AniLibria.TV]_[HDTVRip_1080p_HEVC].mkv")

        assertEquals(1 to 1, run.placeOf("Kimetsu_no_Yaiba_[01]_[AniLibria.TV]_[HDTVRip_1080p_HEVC].mkv"))
    }

    @Test
    fun `an arc release whose season title the provider lacks is skipped instead of going to season 1`() = runTest {
        // TMDB without the arcs' season-tagged titles: nothing says which season Yuukaku-hen is.
        val untagged = FakeTmdb(
            listOf(demonSlayer.copy(alternativeTitles = listOf("Kimetsu no Yaiba", "Demon Slayer"), seasonTitles = emptyMap())),
            seasons = mapOf("85937" to mapOf(1 to 1..26, 3 to 1..11)),
        )

        val run = runProcessor(
            untagged,
            "Kimetsu_no_Yaiba_-_Yuukaku-hen_[10]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv",
            "Demon_Slayer_-_Entertainment_District_Arc_[03]_[1080p].mkv",
        )

        assertTrue(run.organizer.organized.isEmpty())
        assertEquals(2, run.stats.skipped)
        assertEquals(emptyList(), run.provider.seasonFetches)
    }

    @Test
    fun `a show whose own title ends like an arc is not an arc`() = runTest {
        val noahsArc = MediaSearchResult.TvShow(title = "Noah's Arc", year = "2005", tmdbId = "2", provider = "TMDb", matchConfidence = 100.0)

        val run = runProcessor(FakeTmdb(listOf(noahsArc), seasons = mapOf("2" to mapOf(1 to 1..9))), "Noahs_Arc_[01]_[720p].mkv")

        assertEquals(1 to 1, run.placeOf("Noahs_Arc_[01]_[720p].mkv"))
    }

    @Test
    fun `a season from the filename or the command line wins over the title`() = runTest {
        val fromFilename = runProcessor(provider(), "Kimetsu no Yaiba - Hashira Geiko-hen - S01E03.mkv")
        val fromOverride = runProcessor(
            provider(),
            "Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[03]_[AniLibria]_[WEBRip_1080p_HEVC].mkv",
            seasonOverride = 1,
        )

        assertEquals(1 to 3, fromFilename.placeOf("Kimetsu no Yaiba - Hashira Geiko-hen - S01E03.mkv"))
        assertEquals(1 to 3, fromOverride.placeOf("Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[03]_[AniLibria]_[WEBRip_1080p_HEVC].mkv"))
    }
}
