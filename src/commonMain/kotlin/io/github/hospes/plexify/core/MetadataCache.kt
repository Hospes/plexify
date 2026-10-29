package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.AlternativeTitles
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import io.github.hospes.plexify.domain.model.MediaId
import io.github.hospes.plexify.domain.model.MediaSearchResult
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

/** A TMDB record, as its aliases and external IDs are cached: movies and shows are numbered apart. */
internal data class TmdbRecord(val type: Type, val id: String) {
    enum class Type { MOVIE, TV }
}

/** The TMDB record a search result is; null when it has no TMDB ID. */
internal val MediaSearchResult.record: TmdbRecord?
    get() = tmdbId?.let { id ->
        when (this) {
            is MediaSearchResult.Movie -> TmdbRecord(TmdbRecord.Type.MOVIE, id)
            is MediaSearchResult.TvShow -> TmdbRecord(TmdbRecord.Type.TV, id)
        }
    }

/** A search as it is cached: the exact query and year, so only the same search is answered from the cache. */
internal data class SearchKey(val title: String, val year: String?)

/** The ID a record is looked up by, as the record is cached: whether the path named it by title doesn't matter. */
private val MediaId.lookupKey: MediaId get() = copy(fromTitle = false)

/**
 * An in-memory cache for metadata to avoid redundant API calls during a single run.
 * This is particularly useful for processing multiple episodes of the same TV show.
 * Only [MetadataRepository] uses it.
 */
internal class MetadataCache {
    private val showCache = mutableMapOf<ShowKey, List<CanonicalMedia.TvShow>>()
    private val unmatchedShowKeys = mutableSetOf<ShowKey>()
    private val seasonCache = mutableMapOf<Pair<ShowIdentity, Int>, CanonicalMedia.Season>()
    private val episodeGroupCache = mutableMapOf<ShowIdentity, List<CanonicalMedia.EpisodeGroup>>()
    private val externalIdsCache = LookupCache<TmdbRecord, ExternalIds?>()
    private val searchCache = LookupCache<SearchKey, List<MediaSearchResult>>()
    private val movieSearchCache = LookupCache<SearchKey, List<MediaSearchResult>>()
    private val aliasCache = LookupCache<TmdbRecord, AlternativeTitles>()
    private val movieRecordCache = LookupCache<MediaId, MediaSearchResult.Movie?>()
    private val showRecordCache = LookupCache<MediaId, MediaSearchResult.TvShow?>()

    private val showMutex = Mutex()
    private val seasonMutex = Mutex()
    private val episodeGroupMutex = Mutex()

    /** The shows matching a search, best first; the runner-ups back the episode lookup up. */
    suspend fun getShows(key: ShowKey): List<CanonicalMedia.TvShow>? = showMutex.withLock { showCache[key] }

    suspend fun putShows(key: ShowKey, shows: List<CanonicalMedia.TvShow>) = showMutex.withLock { showCache[key] = shows }

    /** Negative cache: remembers show lookups that found no confident match, so they aren't retried. */
    suspend fun isShowUnmatched(key: ShowKey): Boolean = showMutex.withLock { key in unmatchedShowKeys }

    suspend fun markShowUnmatched(key: ShowKey) = showMutex.withLock { unmatchedShowKeys.add(key) }

    suspend fun getSeason(show: ShowIdentity, season: Int): CanonicalMedia.Season? =
        seasonMutex.withLock { seasonCache[show to season] }

    suspend fun putSeason(show: ShowIdentity, season: Int, data: CanonicalMedia.Season) =
        seasonMutex.withLock { seasonCache[show to season] = data }

    /** Episode groups by show; an empty list means the show has none. A failed lookup is not cached. */
    suspend fun getEpisodeGroups(show: ShowIdentity): List<CanonicalMedia.EpisodeGroup>? =
        episodeGroupMutex.withLock { episodeGroupCache[show] }

    suspend fun putEpisodeGroups(show: ShowIdentity, groups: List<CanonicalMedia.EpisodeGroup>) =
        episodeGroupMutex.withLock { episodeGroupCache[show] = groups }

    /** External IDs by TMDB record; a null result (not needed, or the media has none) is cached too. */
    suspend fun getOrPutExternalIds(record: TmdbRecord, lookup: suspend () -> Result<ExternalIds?>): Result<ExternalIds?> =
        externalIdsCache.getOrPut(record, lookup = lookup)

    /** A general search's results, by its exact query and year. */
    suspend fun getOrPutSearch(key: SearchKey, onHit: () -> Unit, lookup: suspend () -> Result<List<MediaSearchResult>>) =
        searchCache.getOrPut(key, onHit, lookup)

    /** A movie search's results, by its exact query and year: another endpoint than the general search's. */
    suspend fun getOrPutMovieSearch(key: SearchKey, onHit: () -> Unit, lookup: suspend () -> Result<List<MediaSearchResult>>) =
        movieSearchCache.getOrPut(key, onHit, lookup)

    /** A TMDB record's aliases, as its lookup answered them; "none" (a 404 too) is cached. */
    suspend fun getOrPutAliases(record: TmdbRecord, onHit: () -> Unit, lookup: suspend () -> Result<AlternativeTitles>) =
        aliasCache.getOrPut(record, onHit, lookup)

    /** The movie an ID names, or null when none has it, by the ID. */
    suspend fun getOrPutMovieRecord(id: MediaId, onHit: () -> Unit, lookup: suspend () -> Result<MediaSearchResult.Movie?>) =
        movieRecordCache.getOrPut(id.lookupKey, onHit, lookup)

    /** The show an ID names, or null when none has it, by the ID. */
    suspend fun getOrPutShowRecord(id: MediaId, onHit: () -> Unit, lookup: suspend () -> Result<MediaSearchResult.TvShow?>) =
        showRecordCache.getOrPut(id.lookupKey, onHit, lookup)
}

/**
 * What lookups answered, by key, for the run. A null answer is kept too; a failed lookup is not, so the next caller
 * tries again. The lock is held through [getOrPut]'s lookup, so concurrent callers asking for the same key wait for the
 * first lookup instead of repeating it.
 */
private class LookupCache<K, V> {
    private val values = mutableMapOf<K, V>()
    private val mutex = Mutex()

    suspend fun getOrPut(key: K, onHit: () -> Unit = {}, lookup: suspend () -> Result<V>): Result<V> = mutex.withLock {
        if (key in values) Result.success(values.getValue(key)).also { onHit() }
        else lookup().onSuccess { values[key] = it }
    }
}
