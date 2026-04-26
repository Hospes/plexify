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
        val season: Int,
        val episode: Int,
        val year: String?,
        override val resolution: String? = null,
        override val quality: String? = null,
        override val hdr: String? = null,
        override val releaseGroup: String? = null,
        override val edition: String? = null,
    ) : ParsedMediaInfo
}