package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.MediaSearchResult

// Search results as a provider returns them, for FakeTmdb: a full match unless matchConfidence says otherwise.

internal fun movieResult(title: String, year: String?, tmdbId: String, matchConfidence: Double = 100.0) =
    MediaSearchResult.Movie(title = title, year = year, tmdbId = tmdbId, provider = "TMDb", matchConfidence = matchConfidence)

internal fun showResult(
    title: String,
    year: String?,
    tmdbId: String,
    matchConfidence: Double = 100.0,
    originalTitle: String? = null,
    alternativeTitles: List<String> = emptyList(),
    seasonTitles: Map<String, Int> = emptyMap(),
) = MediaSearchResult.TvShow(
    title = title,
    year = year,
    tmdbId = tmdbId,
    provider = "TMDb",
    matchConfidence = matchConfidence,
    originalTitle = originalTitle,
    alternativeTitles = alternativeTitles,
    seasonTitles = seasonTitles,
)
