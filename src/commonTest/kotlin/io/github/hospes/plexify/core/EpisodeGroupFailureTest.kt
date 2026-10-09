package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.CanonicalMedia.EpisodeGroup
import io.github.hospes.plexify.domain.model.MediaSearchResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeGroupFailureTest {

    // TMDB keeps both cours in one 24-episode season; the release numbers the second cour as season 2.
    private val gate = listOf(MediaSearchResult.TvShow(title = "Gate", year = "2015", tmdbId = "1", provider = "TMDb", matchConfidence = 100.0))
    private val seasons = mapOf("1" to mapOf(1 to 1..24))
    private val cours = { show: CanonicalMedia.TvShow ->
        val episodes = (1..24).map { CanonicalMedia.Episode(show, 1, it, "Episode $it") }
        listOf(
            EpisodeGroup(
                name = "TV Cours",
                type = EpisodeGroup.Type.PRODUCTION,
                parts = listOf(
                    EpisodeGroup.Part("Cour 1", 1, episodes.take(12)),
                    EpisodeGroup.Part("Cour 2", 2, episodes.drop(12)),
                ),
            )
        )
    }
    private val files = arrayOf("Gate.S02E01.1080p.WEB.mkv", "Gate.S02E02.1080p.WEB.mkv")

    @Test
    fun `episodes whose episode groups fail to load count as failed and fetch them again`() = runTest {
        val provider = FakeTmdb(gate, seasons, episodeGroupsOf = cours, episodeGroupFailures = Int.MAX_VALUE)

        val run = runProcessor(provider, *files)

        assertEquals(2, run.stats.failed)
        assertEquals(0, run.stats.skipped)
        // A failed lookup says nothing about the show's groups, so it is not cached as "no groups".
        assertEquals(listOf("1", "1"), run.provider.episodeGroupFetches)
    }

    @Test
    fun `episode groups that load on a later file are used for it`() = runTest {
        val provider = FakeTmdb(gate, seasons, episodeGroupsOf = cours, episodeGroupFailures = 1)

        val run = runProcessor(provider, *files)

        // Files come in directory order, which only Windows keeps alphabetical: either one may be first.
        assertEquals(1, run.stats.failed)
        assertEquals(1, run.stats.organized)
        assertEquals(1, run.organizer.organized.values.single().let { (it as CanonicalMedia.Episode).season })
    }

    @Test
    fun `a show without episode groups is looked up once and its episodes skipped`() = runTest {
        val provider = FakeTmdb(gate, seasons)

        val run = runProcessor(provider, *files)

        assertEquals(0, run.stats.failed)
        assertEquals(2, run.stats.skipped)
        assertEquals(listOf("1"), run.provider.episodeGroupFetches)
    }
}
