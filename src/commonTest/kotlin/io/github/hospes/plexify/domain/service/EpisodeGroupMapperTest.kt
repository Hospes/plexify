package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.CanonicalMedia.EpisodeGroup
import io.github.hospes.plexify.domain.service.EpisodeGroupMapper.Resolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EpisodeGroupMapperTest {

    private val gate = CanonicalMedia.TvShow(title = "Gate", year = 2015, tmdbId = "63663")

    private fun ep(season: Int, episode: Int) = CanonicalMedia.Episode(gate, season, episode, title = "S${season}E$episode")

    private fun part(order: Int, episodes: List<CanonicalMedia.Episode>, name: String = "Part $order") =
        EpisodeGroup.Part(name = name, order = order, episodes = episodes)

    // TMDB's one 24-episode season, split into the two 12-episode cours releases ship.
    private val tvCours = EpisodeGroup(
        name = "TV Cours",
        type = EpisodeGroup.Type.PRODUCTION,
        parts = listOf(
            part(1, (1..12).map { ep(1, it) }),
            part(2, (13..24).map { ep(1, it) }),
        ),
    )

    @Test
    fun `maps second cour onto the single tmdb season`() {
        val result = EpisodeGroupMapper.resolve(listOf(tvCours), season = 2, episode = 1)

        assertIs<Resolution.Found>(result)
        assertEquals(ep(1, 13), result.episode)
        assertEquals("TV Cours", result.group.name)
    }

    @Test
    fun `maps last episode of second cour`() {
        val result = EpisodeGroupMapper.resolve(listOf(tvCours), season = 2, episode = 12)

        assertIs<Resolution.Found>(result)
        assertEquals(ep(1, 24), result.episode)
    }

    @Test
    fun `not found past the end of a part or the group`() {
        assertEquals(Resolution.NotFound, EpisodeGroupMapper.resolve(listOf(tvCours), season = 2, episode = 13))
        assertEquals(Resolution.NotFound, EpisodeGroupMapper.resolve(listOf(tvCours), season = 3, episode = 1))
        assertEquals(Resolution.NotFound, EpisodeGroupMapper.resolve(emptyList(), season = 2, episode = 1))
    }

    @Test
    fun `leading specials part does not shift the seasons`() {
        val withSpecials = tvCours.copy(parts = listOf(part(0, listOf(ep(0, 1), ep(0, 2)), name = "Specials")) + tvCours.parts)

        val result = EpisodeGroupMapper.resolve(listOf(withSpecials), season = 2, episode = 1)

        assertIs<Resolution.Found>(result)
        assertEquals(ep(1, 13), result.episode)
    }

    @Test
    fun `parts are taken in their order not their listing`() {
        val shuffled = tvCours.copy(parts = tvCours.parts.reversed())

        val result = EpisodeGroupMapper.resolve(listOf(shuffled), season = 2, episode = 1)

        assertIs<Resolution.Found>(result)
        assertEquals(ep(1, 13), result.episode)
    }

    @Test
    fun `ignores absolute and story arc groups`() {
        val arcs = tvCours.copy(name = "Arcs", type = EpisodeGroup.Type.STORY_ARC)
        val absolute = tvCours.copy(name = "Absolute", type = EpisodeGroup.Type.ABSOLUTE)

        assertEquals(Resolution.NotFound, EpisodeGroupMapper.resolve(listOf(arcs, absolute), season = 2, episode = 1))
    }

    // TMDB seasons of 3 and 4 episodes, numbered 1-7 in one run.
    private val absoluteNoSpecials = EpisodeGroup(
        name = "Absolute (No Specials)",
        type = EpisodeGroup.Type.ABSOLUTE,
        parts = listOf(part(1, (1..3).map { ep(1, it) } + (1..4).map { ep(2, it) })),
    )

    @Test
    fun `places an absolute number past season 1 through an absolute group`() {
        val result = EpisodeGroupMapper.resolve(listOf(absoluteNoSpecials), season = 1, episode = 5)

        assertIs<Resolution.Found>(result)
        assertEquals(ep(2, 2), result.episode)
    }

    @Test
    fun `absolute groups with and without specials agree`() {
        val withSpecials = absoluteNoSpecials.copy(
            name = "Absolute (With Specials)",
            parts = listOf(part(1, (1..3).map { ep(1, it) } + ep(0, 1) + (1..4).map { ep(2, it) })),
        )

        val result = EpisodeGroupMapper.resolve(listOf(absoluteNoSpecials, withSpecials), season = 1, episode = 5)

        assertIs<Resolution.Found>(result)
        assertEquals(ep(2, 2), result.episode)
    }

    @Test
    fun `absolute groups place only season 1 numbering`() {
        assertEquals(Resolution.NotFound, EpisodeGroupMapper.resolve(listOf(absoluteNoSpecials), season = 2, episode = 5))
    }

    @Test
    fun `season-like groups win over absolute ones`() {
        val result = EpisodeGroupMapper.resolve(listOf(absoluteNoSpecials, tvCours), season = 1, episode = 5)

        assertIs<Resolution.Found>(result)
        assertEquals("TV Cours", result.group.name)
        assertEquals(ep(1, 5), result.episode)
    }

    @Test
    fun `agreeing groups of the same type resolve`() {
        val copy = tvCours.copy(name = "Cours (copy)")

        val result = EpisodeGroupMapper.resolve(listOf(tvCours, copy), season = 2, episode = 1)

        assertIs<Resolution.Found>(result)
        assertEquals(ep(1, 13), result.episode)
    }

    @Test
    fun `disagreeing groups of the same type are ambiguous`() {
        val thirteenAndEleven = tvCours.copy(
            name = "13 + 11",
            parts = listOf(part(1, (1..13).map { ep(1, it) }), part(2, (14..24).map { ep(1, it) })),
        )

        val result = EpisodeGroupMapper.resolve(listOf(tvCours, thirteenAndEleven), season = 2, episode = 1)

        assertIs<Resolution.Ambiguous>(result)
        assertEquals(listOf("TV Cours", "13 + 11"), result.groups.map { it.name })
    }

    @Test
    fun `more trusted group type wins over a disagreeing one`() {
        val dvd = tvCours.copy(
            name = "DVD",
            type = EpisodeGroup.Type.DVD,
            parts = listOf(part(1, (1..13).map { ep(1, it) }), part(2, (14..24).map { ep(1, it) })),
        )

        val result = EpisodeGroupMapper.resolve(listOf(dvd, tvCours), season = 2, episode = 1)

        assertIs<Resolution.Found>(result)
        assertEquals("TV Cours", result.group.name)
        assertEquals(ep(1, 13), result.episode)
    }

    @Test
    fun `falls back to a less trusted type when the trusted one cannot place the episode`() {
        val shortTv = tvCours.copy(name = "Short", type = EpisodeGroup.Type.TV, parts = listOf(tvCours.parts.first()))

        val result = EpisodeGroupMapper.resolve(listOf(shortTv, tvCours), season = 2, episode = 1)

        assertIs<Resolution.Found>(result)
        assertEquals("TV Cours", result.group.name)
    }
}
