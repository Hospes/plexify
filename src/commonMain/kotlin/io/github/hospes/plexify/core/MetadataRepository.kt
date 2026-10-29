package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaId
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.logging.LoggingContext
import io.github.hospes.plexify.logging.debug
import io.github.hospes.plexify.logging.indent
import kotlin.coroutines.cancellation.CancellationException

/**
 * The run's only way to the metadata providers: it asks [service] and keeps what the run reuses in its own
 * [MetadataCache]. A failed lookup (timeout, HTTP or network error) is never cached, so the next file tries again;
 * what the providers answered is, including "no such season" (an empty season), "no IMDb ID" (null), "no results" and
 * "no such record".
 */
class MetadataRepository(private val service: MetadataService) {
    private val cache = MetadataCache()

    /**
     * The results of a search for [title] and [year], searched once per run: the numbered-season check and the show
     * lookup ask for the same title, and every version of a movie for its own. Only the exact same query and year are
     * answered from the cache. A search that found results while another provider failed is kept as it is (App has
     * TMDB only).
     */
    context(_: LoggingContext)
    suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>> =
        cache.getOrPutSearch(SearchKey(title, year), onHit = { searchHit(title, year) }) { service.search(title, year) }

    /** The movies a search for [title] with a release in [year] found, searched once per run as [search] is. */
    context(_: LoggingContext)
    suspend fun searchMovies(title: String, year: String): Result<List<MediaSearchResult>> =
        cache.getOrPutMovieSearch(SearchKey(title, year), onHit = { searchHit(title, year) }) { service.searchMovies(title, year) }

    context(_: LoggingContext)
    private fun searchHit(title: String, year: String?) =
        indent { debug("Cache HIT for search: '$title'${year?.let { " ($it)" } ?: ""}") }

    /**
     * [results] with their aliases, as [MetadataService.withAlternativeTitles] looks them up. Each record's aliases are
     * fetched once per run and kept as TMDB answered them (a 404 as none), for the next search whose lookups pick the
     * record; which results get aliases is still decided per search, so a cached set reaches only results a fetch
     * would have. A failed lookup is not kept.
     */
    context(_: LoggingContext)
    suspend fun withAlternativeTitles(results: List<MediaSearchResult>, title: String): Result<List<MediaSearchResult>> =
        service.withAlternativeTitles(results, title) { result, fetch ->
            val record = result.record
            if (record == null) fetch()
            else cache.getOrPutAliases(record, onHit = { indent { debug("Cache HIT for alternative titles of '${result.title}'") } }) {
                runCatching { fetch() }.onFailure { if (it is CancellationException) throw it }
            }.getOrThrow()
        }

    /** The movie [id] names, or null when no provider has it; looked up once per ID and run, as every version asks. */
    context(_: LoggingContext)
    suspend fun findMovie(id: MediaId): Result<MediaSearchResult.Movie?> =
        cache.getOrPutMovieRecord(id, onHit = { recordHit(id) }) { service.findMovie(id) }

    /** The show [id] names, or null when no provider has it; looked up once per ID and run. */
    context(_: LoggingContext)
    suspend fun findShow(id: MediaId): Result<MediaSearchResult.TvShow?> =
        cache.getOrPutShowRecord(id, onHit = { recordHit(id) }) { service.findShow(id) }

    context(_: LoggingContext)
    private fun recordHit(id: MediaId) = indent { debug("Cache HIT for $id") }

    /**
     * The shows a lookup for [key] matched, best first; empty when it matched none (see [markShowUnmatched]); null when
     * [key] hasn't been looked up yet, or its lookup failed.
     */
    internal suspend fun cachedShows(key: ShowKey): List<CanonicalMedia.TvShow>? =
        cache.getShows(key) ?: emptyList<CanonicalMedia.TvShow>().takeIf { cache.isShowUnmatched(key) }

    /** Keeps the shows a lookup for [key] matched, best first; the runner-ups back the episode lookup up. */
    internal suspend fun putShows(key: ShowKey, shows: List<CanonicalMedia.TvShow>) = cache.putShows(key, shows)

    /**
     * Negative cache: the lookup for [key] succeeded but matched no show, so later files of the show don't repeat it
     * (and its output). Only for a lookup that succeeded: a failed one is not cached, so the next file tries again.
     */
    internal suspend fun markShowUnmatched(key: ShowKey) = cache.markShowUnmatched(key)

    /**
     * The whole season (one API call), fetched once per show and season, so the season's other episodes come from the
     * cache; empty when the providers report it as nonexistent. A failed fetch is not cached.
     */
    context(_: LoggingContext)
    suspend fun season(show: CanonicalMedia.TvShow, season: Int): Result<CanonicalMedia.Season> {
        val identity = show.identity
        cache.getSeason(identity, season)?.let { cachedSeason ->
            if (cachedSeason.episodes.isNotEmpty()) {
                debug("Cache HIT for S${season} (${cachedSeason.episodes.size} episodes loaded)")
            }
            return Result.success(cachedSeason)
        }

        debug("Cache MISS for season: S${season}. Fetching from providers...")
        return service.getSeason(show, season).onSuccess { cache.putSeason(identity, season, it) }
    }

    /** True when the providers reported the season as nonexistent (a failed fetch is not cached). */
    suspend fun isSeasonMissing(show: CanonicalMedia.TvShow, season: Int): Boolean =
        cache.getSeason(show.identity, season)?.episodes?.isEmpty() == true

    /** The show's episode groups, fetched once per show; empty when it has none. A failed lookup is not cached. */
    context(_: LoggingContext)
    suspend fun episodeGroups(show: CanonicalMedia.TvShow): Result<List<CanonicalMedia.EpisodeGroup>> {
        val identity = show.identity
        cache.getEpisodeGroups(identity)?.let { return Result.success(it) }
        return service.getEpisodeGroups(show).onSuccess { cache.putEpisodeGroups(identity, it) }
    }

    /**
     * The movie with the IMDb ID that search results don't carry. Looked up once per TMDB record and run (the service
     * skips it when the template doesn't use `{imdbid}`), so several versions of a movie cost one call; "no IMDb ID" is
     * kept too. A failed lookup fails the file, as a failed season fetch does, and is tried again for the next one:
     * named without the ID, the file would land outside the folder the next run files the title in.
     */
    context(_: LoggingContext)
    suspend fun withImdbId(movie: CanonicalMedia.Movie): Result<CanonicalMedia.Movie> =
        imdbId(movie, movie.imdbId, movie.tmdbId, TmdbRecord.Type.MOVIE).map { id -> id?.let { movie.copy(imdbId = it) } ?: movie }

    /** The show with its IMDb ID, looked up as a movie's is; a season of episodes costs one call. */
    context(_: LoggingContext)
    suspend fun withImdbId(show: CanonicalMedia.TvShow): Result<CanonicalMedia.TvShow> =
        imdbId(show, show.imdbId, show.tmdbId, TmdbRecord.Type.TV).map { id -> id?.let { show.copy(imdbId = it) } ?: show }

    /** The IMDb ID [media] lacks, from the cache or the providers; null when it has one already, or none is known. */
    context(_: LoggingContext)
    private suspend fun imdbId(media: CanonicalMedia, imdbId: String?, tmdbId: String?, type: TmdbRecord.Type): Result<String?> {
        if (imdbId != null || tmdbId == null) return Result.success(null)
        return cache.getOrPutExternalIds(TmdbRecord(type, tmdbId)) { service.getExternalIds(media) }.map { it?.imdbId }
    }
}
