package io.github.hospes.plexify.domain.model

// The "golden record" for a piece of media after verification.
data class CanonicalMedia(
    val title: String,
    val year: Int,
    val imdbId: String? = null,
    val tmdbId: String? = null
)