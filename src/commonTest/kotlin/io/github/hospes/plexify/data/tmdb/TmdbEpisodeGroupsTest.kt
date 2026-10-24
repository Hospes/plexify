package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.CanonicalMedia.EpisodeGroup.Type
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `tv/{id}/episode_groups` and the `tv/episode_group/{id}` lookups it leads to: which groups are fetched, in what
 * order. Bodies are hand-written in the shape of TMDB's (October 2026); TMDB numbers the group types 1 to 7.
 */
class TmdbEpisodeGroupsTest {

    private val onePiece = CanonicalMedia.TvShow(title = "One Piece", year = 1999, tmdbId = "37854")

    private fun summary(id: String, type: Int) =
        """{"description":"","episode_count":1100,"group_count":21,"id":"$id","name":"Group $id","network":null,"type":$type}"""

    private fun group(id: String, type: Int, parts: String = part("Part 1", 0, episode(1, 1, 0))) =
        "/3/tv/episode_group/$id" to
            """{"description":"","episode_count":1,"group_count":1,"groups":[$parts],"id":"$id","name":"Group $id","network":null,"type":$type}"""

    private fun part(name: String, order: Int, vararg episodes: String) =
        """{"id":"p$order","name":"$name","order":$order,"episodes":[${episodes.joinToString(",")}],"locked":true}"""

    private fun episode(season: Int, number: Int, order: Int) =
        """{"air_date":"2019-04-06","episode_number":$number,"id":${season * 1000 + number},"name":"Episode $number","order":$order,"season_number":$season,"show_id":37854}"""

    /** A TMDB with the show's group summaries ([types] by group ID, in TMDB's order) and every group's body. */
    private fun tmdb(vararg types: Pair<String, Int>) = TmdbResponder(
        mapOf("/3/tv/37854/episode_groups" to """{"id":37854,"results":[${types.joinToString(",") { (id, type) -> summary(id, type) }}]}""") +
            types.map { (id, type) -> group(id, type) },
    )

    private fun TmdbResponder.groupLookups() = paths.drop(1).map { it.removePrefix("/3/tv/episode_group/") }

    private fun provider(tmdb: TmdbResponder) = mockProvider(credentials = tokenCredentials, handler = tmdb.handler)

    @Test
    fun `season-like groups are fetched most trusted first and then absolute ones`() = runBlocking {
        // In TMDB's numbering: original air date, absolute, DVD, digital, story arc, production, TV, and an unknown 8.
        val tmdb = tmdb("air" to 1, "abs" to 2, "dvd" to 3, "digital" to 4, "arc" to 5, "production" to 6, "tv" to 7, "new" to 8)

        val groups = provider(tmdb).episodeGroups(onePiece).getOrThrow()

        assertEquals(listOf("tv", "production", "air", "digital", "dvd", "abs"), tmdb.groupLookups())
        assertEquals(listOf(Type.TV, Type.PRODUCTION, Type.ORIGINAL_AIR_DATE, Type.DIGITAL, Type.DVD, Type.ABSOLUTE), groups.map { it.type })
    }

    @Test
    fun `at most six season-like and two absolute groups are fetched`() = runBlocking {
        // The DVD group comes first in TMDB's list but is the least trusted, so it is the one left out.
        val tmdb = tmdb("dvd" to 3, *(1..7).map { "tv$it" to 7 }.toTypedArray(), "abs1" to 2, "abs2" to 2, "abs3" to 2)

        provider(tmdb).episodeGroups(onePiece).getOrThrow()

        assertEquals(listOf("tv1", "tv2", "tv3", "tv4", "tv5", "tv6", "abs1", "abs2"), tmdb.groupLookups())
    }

    @Test
    fun `a group's parts keep TMDB's numbering of their episodes in the group's order`() = runBlocking {
        val tmdb = TmdbResponder(
            mapOf(
                "/3/tv/37854/episode_groups" to """{"id":37854,"results":[${summary("cours", 7)}]}""",
                group(
                    "cours", 7,
                    part("Cour 1", 1, episode(1, 1, 0), episode(1, 2, 1)) + "," +
                        part("Cour 2", 2, episode(1, 14, 1), episode(1, 13, 0)),
                ),
            ),
        )

        val group = provider(tmdb).episodeGroups(onePiece).getOrThrow().single()

        assertEquals("Group cours", group.name)
        assertEquals(listOf("Cour 1" to 1, "Cour 2" to 2), group.parts.map { it.name to it.order })
        assertEquals(
            listOf(listOf(1 to 1, 1 to 2), listOf(1 to 13, 1 to 14)),
            group.parts.map { part -> part.episodes.map { it.season to it.episode } },
        )
        assertEquals("Episode 13", group.parts[1].episodes[0].title)
    }

    @Test
    fun `a show without episode groups costs one request`() = runBlocking {
        val tmdb = tmdb()

        val groups = provider(tmdb).episodeGroups(onePiece).getOrThrow()

        assertEquals(emptyList(), groups)
        assertEquals(listOf("/3/tv/37854/episode_groups"), tmdb.paths)
    }
}
