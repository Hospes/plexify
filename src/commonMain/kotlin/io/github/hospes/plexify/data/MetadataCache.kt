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
    private val seasonCache = mutableMapOf<String, CanonicalMedia.Season>()

    private val showMutex = Mutex()
    private val seasonMutex = Mutex()

    suspend fun getShow(key: String): CanonicalMedia.TvShow? = showMutex.withLock { showCache[key] }

    suspend fun putShow(key: String, show: CanonicalMedia.TvShow) = showMutex.withLock { showCache[key] = show }

    suspend fun getSeason(key: String): CanonicalMedia.Season? = seasonMutex.withLock { seasonCache[key] }

    suspend fun putSeason(key: String, season: CanonicalMedia.Season) = seasonMutex.withLock { seasonCache[key] = season }
}