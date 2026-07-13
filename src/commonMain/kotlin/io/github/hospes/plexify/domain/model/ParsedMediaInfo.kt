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
    ) : ParsedMediaInfo
}

/**
 * Applies user-provided CLI overrides on top of what was parsed from the filename.
 * A season override only makes sense for episodes and is ignored for movies.
 */
fun ParsedMediaInfo.withOverrides(title: String?, season: Int?, year: String? = null): ParsedMediaInfo = when (this) {
    is ParsedMediaInfo.Movie -> copy(
        title = title ?: this.title,
        year = year ?: this.year,
    )
    is ParsedMediaInfo.Episode -> copy(
        showTitle = title ?: showTitle,
        season = season ?: this.season,
        year = year ?: this.year,
    )
}