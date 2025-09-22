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
    private val episodeCache = mutableMapOf<String, CanonicalMedia.Episode>()

    private val showMutex = Mutex()
    private val episodeMutex = Mutex()

    /**
     * Retrieves a cached TV show.
     * @param key A unique identifier for the show, typically "title:year".
     */
    suspend fun getShow(key: String): CanonicalMedia.TvShow? = showMutex.withLock {
        showCache[key]
    }

    /**
     * Caches a TV show.
     * @param key A unique identifier for the show.
     * @param show The show object to cache.
     */
    suspend fun putShow(key: String, show: CanonicalMedia.TvShow) = showMutex.withLock {
        showCache[key] = show
    }

    /**
     * Retrieves a cached episode.
     * @param key A unique identifier for the episode, typically "showId:season:episode".
     */
    suspend fun getEpisode(key: String): CanonicalMedia.Episode? = episodeMutex.withLock {
        episodeCache[key]
    }

    /**
     * Caches an episode.
     * @param key A unique identifier for the episode.
     * @param episode The episode object to cache.
     */
    suspend fun putEpisode(key: String, episode: CanonicalMedia.Episode) = episodeMutex.withLock {
        episodeCache[key] = episode
    }
}