package io.github.hospes.plexify.domain.model

/** IDs other databases use for a matched movie or show. TVDb numbers shows only. */
data class ExternalIds(
    val imdbId: String? = null,
    val tvdbId: String? = null,
)
