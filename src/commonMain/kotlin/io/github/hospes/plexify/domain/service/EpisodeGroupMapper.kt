package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.CanonicalMedia.EpisodeGroup

/**
 * Maps a release's season/episode numbering onto the provider's own numbering through an
 * alternative ordering (episode group). Anime is the usual case: TMDB keeps "Gate" as one
 * 24-episode season, while releases ship it as two 12-episode seasons, so S2E01 of the
 * release is S1E13 on TMDB. The "TV Cours" episode group records exactly that split.
 */
object EpisodeGroupMapper {

    /** Group types whose parts can stand in for seasons, most trusted first. */
    val SEASON_LIKE_TYPES: List<EpisodeGroup.Type> = listOf(
        EpisodeGroup.Type.TV,
        EpisodeGroup.Type.PRODUCTION,
        EpisodeGroup.Type.ORIGINAL_AIR_DATE,
        EpisodeGroup.Type.DIGITAL,
        EpisodeGroup.Type.DVD,
    )

    sealed interface Resolution {
        data class Found(
            val episode: CanonicalMedia.Episode,
            val group: EpisodeGroup,
            val part: EpisodeGroup.Part,
        ) : Resolution

        /** Equally trusted groups place the episode differently; guessing could mislabel it. */
        data class Ambiguous(val groups: List<EpisodeGroup>) : Resolution

        data object NotFound : Resolution
    }

    fun resolve(groups: List<EpisodeGroup>, season: Int, episode: Int): Resolution {
        if (season < 1 || episode < 1) return Resolution.NotFound

        val candidates = groups
            .filter { it.type in SEASON_LIKE_TYPES }
            .mapNotNull { group ->
                val part = group.seasonParts().getOrNull(season - 1) ?: return@mapNotNull null
                val target = part.episodes.getOrNull(episode - 1) ?: return@mapNotNull null
                Resolution.Found(target, group, part)
            }
        if (candidates.isEmpty()) return if (season == 1) resolveAbsolute(groups, episode) else Resolution.NotFound

        // Trust the most reliable group type that can place the episode at all; within that
        // type, groups must agree on the target episode.
        val bestType = SEASON_LIKE_TYPES.first { type -> candidates.any { it.group.type == type } }
        val tier = candidates.filter { it.group.type == bestType }
        return agreeing(tier)
    }

    /**
     * Season 1 running past every season-like part is how absolute numbering reads: "One Piece - 1071"
     * has no season, so it arrives as S1E1071. Its position in an absolute ordering, specials left
     * out, is the episode.
     */
    private fun resolveAbsolute(groups: List<EpisodeGroup>, episode: Int): Resolution {
        val candidates = groups
            .filter { it.type == EpisodeGroup.Type.ABSOLUTE }
            .mapNotNull { group ->
                val (part, target) = group.seasonParts()
                    .flatMap { part -> part.episodes.map { part to it } }
                    .filter { (_, it) -> it.season != 0 }
                    .getOrNull(episode - 1) ?: return@mapNotNull null
                Resolution.Found(target, group, part)
            }
        return if (candidates.isEmpty()) Resolution.NotFound else agreeing(candidates)
    }

    private fun agreeing(candidates: List<Resolution.Found>): Resolution {
        val targets = candidates.distinctBy { it.episode.season to it.episode.episode }
        return if (targets.size == 1) candidates.first() else Resolution.Ambiguous(candidates.map { it.group })
    }

    /**
     * The group's parts in season order, without a specials part: groups often lead with
     * TMDB's season 0, which would otherwise shift every season by one.
     */
    private fun EpisodeGroup.seasonParts(): List<EpisodeGroup.Part> = parts
        .filterNot { part -> part.episodes.isNotEmpty() && part.episodes.all { it.season == 0 } }
        .sortedBy { it.order }
}
