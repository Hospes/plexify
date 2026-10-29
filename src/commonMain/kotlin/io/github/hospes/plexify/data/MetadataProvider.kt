package io.github.hospes.plexify.data

import io.github.hospes.plexify.domain.model.AlternativeTitles
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import io.github.hospes.plexify.domain.model.MediaId
import io.github.hospes.plexify.domain.model.MediaSearchResult

interface MetadataProvider {
    val id: String
    val supportedIds: Set<String>

    suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>>

    /**
     * Movies titled like [title] with a release in [year]: a narrower second search for when [search]
     * misses the film, e.g. a remake whose namesakes fill the first page. A provider whose [search]
     * already filters by year returns nothing.
     */
    suspend fun searchMovies(title: String, year: String): Result<List<MediaSearchResult>> = Result.success(emptyList())

    /**
     * [results], this provider's answer to a [search] or [searchMovies] for [title], with the aliases its search results
     * don't carry (translations, romanizations), for the results that need them. The caller asks only when the aliases
     * could change the match (MatchRanker.aliasesCanChangeWinner). Each record's aliases come through [cache], which
     * fetches them only when it doesn't have them. Fails when a lookup failed and the missing alias may be the match.
     */
    suspend fun withAlternativeTitles(
        results: List<MediaSearchResult>,
        title: String,
        cache: AliasCache = AliasCache.NONE,
    ): Result<List<MediaSearchResult>> = Result.success(results)

    /**
     * The movie [id] names, with every title it is known by and its IMDb ID when the provider has them; its
     * [MediaSearchResult.matchConfidence] is 0. Fails with [MetadataNotFoundException] when there is no such movie.
     */
    suspend fun movie(id: MediaId): Result<MediaSearchResult.Movie> =
        Result.failure(UnsupportedOperationException("movie() not supported by provider '${this.id}'"))

    /** The show [id] names, as [movie] does a movie, with the titles its seasons have of their own. */
    suspend fun show(id: MediaId): Result<MediaSearchResult.TvShow> =
        Result.failure(UnsupportedOperationException("show() not supported by provider '${this.id}'"))

    suspend fun season(show: CanonicalMedia.TvShow, season: Int): Result<CanonicalMedia.Season> =
        Result.failure(UnsupportedOperationException("season() not supported by provider '$id'"))

    suspend fun episodeGroups(show: CanonicalMedia.TvShow): Result<List<CanonicalMedia.EpisodeGroup>> =
        Result.failure(UnsupportedOperationException("episodeGroups() not supported by provider '$id'"))

    /** Other databases' IDs for a matched movie or show; a field is null when the provider knows of none. */
    suspend fun externalIds(media: CanonicalMedia): Result<ExternalIds> =
        Result.failure(UnsupportedOperationException("externalIds() not supported by provider '$id'"))
}

/**
 * Where [MetadataProvider.withAlternativeTitles] gets a record's aliases: the run's cache, so a record that several
 * searches find has its aliases fetched once. Which results get them, and what a failed fetch means, stays the provider's.
 */
fun interface AliasCache {
    /** The aliases of [result]'s record: the ones kept, else [fetch]'s, kept when it returns. A failed fetch throws and isn't kept. */
    suspend fun getOrFetch(result: MediaSearchResult, fetch: suspend () -> AlternativeTitles): AlternativeTitles

    companion object {
        /** Keeps nothing: every lookup fetches. */
        val NONE = AliasCache { _, fetch -> fetch() }
    }
}

/** The provider has no such record (HTTP 404), e.g. a season it numbers differently. Expected, not an error. */
class MetadataNotFoundException(message: String) : Exception(message)

/**
 * The provider did not answer in time. Says nothing about whether the record exists, so a file whose
 * search times out counts as failed, not skipped. Carries no cause, so no stack trace prints the
 * request URL (which can contain an API key).
 */
class MetadataTimeoutException(message: String) : Exception(message)

/** The connection to the provider failed (DNS, refused, TLS, ...). Like [MetadataTimeoutException], it carries no cause. */
class MetadataConnectionException(message: String) : Exception(message)

/**
 * The provider stopped answering: several requests in a row timed out or could not connect, so the
 * remaining ones of the run fail at once instead of each waiting out the timeouts. The files still
 * count as failed.
 */
class MetadataUnreachableException(message: String) : Exception(message)
