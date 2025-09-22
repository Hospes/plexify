package io.github.hospes.plexify.data

import io.github.hospes.plexify.core.levenshtein
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import kotlin.math.max

interface MetadataProvider {
    suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>>
    suspend fun episode(show: CanonicalMedia.TvShow, season: Int, episode: Int): Result<CanonicalMedia.Episode>
}

internal fun calculateTitleConfidence(query: String, resultTitle: String): Double {
    val distance = levenshtein(query.lowercase(), resultTitle.lowercase())
    val titleLength = max(query.length, resultTitle.length)
    return if (titleLength > 0) (1.0 - (distance.toDouble() / titleLength)) * 100.0 else 0.0
}