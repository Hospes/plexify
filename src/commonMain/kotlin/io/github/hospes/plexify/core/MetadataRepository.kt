package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaId
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.logging.LoggingContext
import io.github.hospes.plexify.logging.debug

/**
 * The run's only way to the metadata providers: it asks [service] and keeps what the run reuses in its own
 * [MetadataCache]. A failed lookup (timeout, HTTP or network error) is never cached, so the next file tries again;
 * what the providers answered is, including "no such season" (an empty season) and "no IMDb ID" (null).
 */
class MetadataRepository(private val service: MetadataService) {
    private val cache = MetadataCache()

    context(_: LoggingContext)
    suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>> = service.search(title, year)

    context(_: LoggingContext)
    suspend fun searchMovies(title: String, year: String): Result<List<MediaSearchResult>> = service.searchMovies(title, year)

    context(_: LoggingContext)
    suspend fun findMovie(id: MediaId): Result<MediaSearchResult.Movie?> = service.findMovie(id)

    context(_: LoggingContext)
    suspend fun findShow(id: MediaId): Result<MediaSearchResult.TvShow?> = service.findShow(id)

    /**
     * The shows a lookup for [key] matched, best first; empty when it matched none (see [markShowFailed]); null when
     * [key] hasn't been looked up yet, or its lookup failed.
     */
    internal suspend fun cachedShows(key: ShowKey): List<CanonicalMedia.TvShow>? =
        cache.getShows(key) ?: emptyList<CanonicalMedia.TvShow>().takeIf { cache.isShowFailed(key) }

    /** Keeps the shows a lookup for [key] matched, best first; the runner-ups back the episode lookup up. */
    internal suspend fun putShows(key: ShowKey, shows: List<CanonicalMedia.TvShow>) = cache.putShows(key, shows)

    /**
     * Negative cache: the lookup for [key] succeeded but matched no show, so later files of the show don't repeat it
     * (and its output). Only for a lookup that succeeded: a failed one is not cached, so the next file tries again.
     */
    internal suspend fun markShowFailed(key: ShowKey) = cache.markShowFailed(key)

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
     * [media] with the IMDb ID that search results don't carry, for a movie or show that lacks it. Looked up once per
     * TMDB record and run (the service skips it when the template doesn't use `{imdbid}`), so a season of episodes or
     * several versions of a movie cost one call; "no IMDb ID" is kept too. A failed lookup fails the file, as a failed
     * season fetch does, and is tried again for the next one: named without the ID, the file would land outside the
     * folder the next run files the title in.
     */
    context(_: LoggingContext)
    suspend fun <M : CanonicalMedia> withImdbId(media: M): Result<M> {
        val record = when (media) {
            is CanonicalMedia.Movie -> media.tmdbId?.takeIf { media.imdbId == null }?.let { TmdbRecord(TmdbRecord.Type.MOVIE, it) }
            is CanonicalMedia.TvShow -> media.tmdbId?.takeIf { media.imdbId == null }?.let { TmdbRecord(TmdbRecord.Type.TV, it) }
            else -> null
        } ?: return Result.success(media)
        return cache.getOrPutExternalIds(record) { service.getExternalIds(media) }.map { ids ->
            val imdbId = ids?.imdbId ?: return@map media
            @Suppress("UNCHECKED_CAST")
            when (media) {
                is CanonicalMedia.Movie -> media.copy(imdbId = imdbId) as M
                is CanonicalMedia.TvShow -> media.copy(imdbId = imdbId) as M
                else -> media
            }
        }
    }
}
