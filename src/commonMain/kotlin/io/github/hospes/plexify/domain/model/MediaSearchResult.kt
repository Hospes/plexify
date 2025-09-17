package io.github.hospes.plexify.domain.model

data class MediaSearchResult(
    val title: String,
    val year: String?,
    // Use specific fields for IDs. This is crucial.
    val imdbId: String? = null,
    val tmdbId: String? = null,
    // Keep track of where this result came from.
    val provider: String,
    // Add a confidence score for later use.
    val matchConfidence: Int = 0
)