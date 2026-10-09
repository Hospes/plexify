package io.github.hospes.plexify.domain.model

sealed interface ParsedMediaInfo {
    val resolution: String?
    val quality: String?
    val hdr: String?
    val releaseGroup: String?
    val edition: String?

    data class Movie(
        val title: String,
        val year: String?,
        override val resolution: String? = null,
        override val quality: String? = null,
        override val hdr: String? = null,
        override val releaseGroup: String? = null,
        override val edition: String? = null,
    ) : ParsedMediaInfo

    data class Episode(
        val showTitle: String,
        val season: Int?,
        val episode: Int,
        val year: String?,
        override val resolution: String? = null,
        override val quality: String? = null,
        override val hdr: String? = null,
        override val releaseGroup: String? = null,
        override val edition: String? = null,
        /** The last episode of a multi-episode file (`S01E01-E03` → 3); null for a single episode. */
        val lastEpisode: Int? = null,
        /**
         * [year] closed the show name without brackets ("Doctor.Who.2005.S01E01"), so it may belong to the
         * title instead ("Space.1999.S01E01" is *Space: 1999*).
         */
        val bareShowYear: Boolean = false,
    ) : ParsedMediaInfo
}

/**
 * Applies user-provided CLI overrides on top of what was parsed from the filename.
 * Season and episode-offset overrides only make sense for episodes and are ignored for movies.
 */
fun ParsedMediaInfo.withOverrides(
    title: String?,
    season: Int?,
    year: String? = null,
    episodeOffset: Int? = null,
): ParsedMediaInfo = when (this) {
    is ParsedMediaInfo.Movie -> copy(
        title = title ?: this.title,
        year = year ?: this.year,
    )
    is ParsedMediaInfo.Episode -> copy(
        showTitle = title ?: showTitle,
        season = season ?: this.season,
        episode = episode + (episodeOffset ?: 0),
        lastEpisode = lastEpisode?.plus(episodeOffset ?: 0),
        year = year ?: this.year,
    )
}