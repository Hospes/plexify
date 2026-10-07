package io.github.hospes.plexify.data

import io.github.hospes.plexify.domain.model.CanonicalMedia
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-memory cache for metadata to avoid redundant API calls during a single run.
 * This is particularly useful for processing multiple episodes of the same TV show.
 */
class MetadataCache {
    private val showCache = mutableMapOf<String, CanonicalMedia.TvShow>()
    private val failedShowKeys = mutableSetOf<String>()
    private val seasonCache = mutableMapOf<String, CanonicalMedia.Season>()
    private val episodeGroupCache = mutableMapOf<String, List<CanonicalMedia.EpisodeGroup>>()

    private val showMutex = Mutex()
    private val seasonMutex = Mutex()
    private val episodeGroupMutex = Mutex()

    suspend fun getShow(key: String): CanonicalMedia.TvShow? = showMutex.withLock { showCache[key] }

    suspend fun putShow(key: String, show: CanonicalMedia.TvShow) = showMutex.withLock { showCache[key] = show }

    /** Negative cache: remembers show lookups that found no confident match, so they aren't retried. */
    suspend fun isShowFailed(key: String): Boolean = showMutex.withLock { key in failedShowKeys }

    suspend fun markShowFailed(key: String) = showMutex.withLock { failedShowKeys.add(key) }

    suspend fun getSeason(key: String): CanonicalMedia.Season? = seasonMutex.withLock { seasonCache[key] }

    suspend fun putSeason(key: String, season: CanonicalMedia.Season) = seasonMutex.withLock { seasonCache[key] = season }

    /** Episode groups by show; an empty list means the show has none (or they failed to load). */
    suspend fun getEpisodeGroups(showKey: String): List<CanonicalMedia.EpisodeGroup>? =
        episodeGroupMutex.withLock { episodeGroupCache[showKey] }

    suspend fun putEpisodeGroups(showKey: String, groups: List<CanonicalMedia.EpisodeGroup>) =
        episodeGroupMutex.withLock { episodeGroupCache[showKey] = groups }
}