package io.github.hospes.plexify.data

import io.github.hospes.plexify.core.levenshtein
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import io.github.hospes.plexify.domain.model.MediaSearchResult
import kotlin.math.max

interface MetadataProvider {
    val id: String
    val supportedIds: Set<String>

    suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>>

    suspend fun episode(show: CanonicalMedia.TvShow, season: Int, episode: Int): Result<CanonicalMedia.Episode> =
        Result.failure(UnsupportedOperationException("episode() not supported by provider '$id'"))

    suspend fun season(show: CanonicalMedia.TvShow, season: Int): Result<CanonicalMedia.Season> =
        Result.failure(UnsupportedOperationException("season() not supported by provider '$id'"))

    suspend fun episodeGroups(show: CanonicalMedia.TvShow): Result<List<CanonicalMedia.EpisodeGroup>> =
        Result.failure(UnsupportedOperationException("episodeGroups() not supported by provider '$id'"))

    /** Other databases' IDs for a matched movie or show; a field is null when the provider knows of none. */
    suspend fun externalIds(media: CanonicalMedia): Result<ExternalIds> =
        Result.failure(UnsupportedOperationException("externalIds() not supported by provider '$id'"))
}

/** The provider has no such record (HTTP 404), e.g. a season it numbers differently. Expected, not an error. */
class MetadataNotFoundException(message: String) : Exception(message)

internal fun calculateTitleConfidence(query: String, resultTitle: String): Double {
    val distance = levenshtein(query.lowercase(), resultTitle.lowercase())
    val titleLength = max(query.length, resultTitle.length)
    return if (titleLength > 0) (1.0 - (distance.toDouble() / titleLength)) * 100.0 else 0.0
}