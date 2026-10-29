package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The provider show a season or episode groups belong to: its TMDB ID, else its IMDb ID, else its title. */
internal sealed interface ShowIdentity {
    data class Tmdb(val id: String) : ShowIdentity
    data class Imdb(val id: String) : ShowIdentity
    data class Title(val title: String) : ShowIdentity
}

/** The provider show this is, as its seasons and episode groups are cached. */
internal val CanonicalMedia.TvShow.identity: ShowIdentity
    get() = tmdbId?.let(ShowIdentity::Tmdb) ?: imdbId?.let(ShowIdentity::Imdb) ?: ShowIdentity.Title(title)

/** A TMDB record, as its external IDs are cached: movies and shows are numbered apart. */
internal data class TmdbRecord(val type: Type, val id: String) {
    enum class Type { MOVIE, TV }
}

/**
 * An in-memory cache for metadata to avoid redundant API calls during a single run.
 * This is particularly useful for processing multiple episodes of the same TV show.
 * Only [MetadataRepository] uses it, which decides what is kept.
 */
internal class MetadataCache {
    private val showCache = mutableMapOf<ShowKey, List<CanonicalMedia.TvShow>>()
    private val failedShowKeys = mutableSetOf<ShowKey>()
    private val seasonCache = mutableMapOf<Pair<ShowIdentity, Int>, CanonicalMedia.Season>()
    private val episodeGroupCache = mutableMapOf<ShowIdentity, List<CanonicalMedia.EpisodeGroup>>()
    private val externalIdsCache = mutableMapOf<TmdbRecord, ExternalIds?>()

    private val showMutex = Mutex()
    private val seasonMutex = Mutex()
    private val episodeGroupMutex = Mutex()
    private val externalIdsMutex = Mutex()

    /** The shows matching a search, best first; the runner-ups back the episode lookup up. */
    suspend fun getShows(key: ShowKey): List<CanonicalMedia.TvShow>? = showMutex.withLock { showCache[key] }

    suspend fun putShows(key: ShowKey, shows: List<CanonicalMedia.TvShow>) = showMutex.withLock { showCache[key] = shows }

    /** Negative cache: remembers show lookups that found no confident match, so they aren't retried. */
    suspend fun isShowFailed(key: ShowKey): Boolean = showMutex.withLock { key in failedShowKeys }

    suspend fun markShowFailed(key: ShowKey) = showMutex.withLock { failedShowKeys.add(key) }

    suspend fun getSeason(show: ShowIdentity, season: Int): CanonicalMedia.Season? =
        seasonMutex.withLock { seasonCache[show to season] }

    suspend fun putSeason(show: ShowIdentity, season: Int, data: CanonicalMedia.Season) =
        seasonMutex.withLock { seasonCache[show to season] = data }

    /** Episode groups by show; an empty list means the show has none. A failed lookup is not cached. */
    suspend fun getEpisodeGroups(show: ShowIdentity): List<CanonicalMedia.EpisodeGroup>? =
        episodeGroupMutex.withLock { episodeGroupCache[show] }

    suspend fun putEpisodeGroups(show: ShowIdentity, groups: List<CanonicalMedia.EpisodeGroup>) =
        episodeGroupMutex.withLock { episodeGroupCache[show] = groups }

    /**
     * External IDs by TMDB record, looked up once per run. A null result (not needed, or the media has
     * none) is cached too; a failed lookup is not, so the next caller tries again. The lock is held
     * through [lookup], so concurrent callers asking for the same record wait for the first lookup
     * instead of repeating it.
     */
    suspend fun getOrPutExternalIds(record: TmdbRecord, lookup: suspend () -> Result<ExternalIds?>): Result<ExternalIds?> =
        externalIdsMutex.withLock {
            if (record in externalIdsCache) Result.success(externalIdsCache[record])
            else lookup().onSuccess { externalIdsCache[record] = it }
        }
}
