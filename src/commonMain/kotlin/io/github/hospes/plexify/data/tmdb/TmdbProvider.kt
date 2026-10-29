package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.AliasCache
import io.github.hospes.plexify.data.MetadataNotFoundException
import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.data.createHttpClientEngine
import io.github.hospes.plexify.data.tmdb.dto.TmdbAlternativeTitlesDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbEpisodeGroupDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbEpisodeGroupsDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbExternalIdsDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbFindResponseDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbMovieDetailsDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbMovieSearchResponseDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbSearchResponseDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbSeasonDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbTvDetailsDto
import io.github.hospes.plexify.domain.matching.MatchScoring
import io.github.hospes.plexify.domain.matching.calculateTitleConfidence
import io.github.hospes.plexify.domain.model.AlternativeTitles
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import io.github.hospes.plexify.domain.model.MediaId
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.service.EpisodeGroupMapper
import io.ktor.client.engine.*
import io.ktor.client.request.*
import kotlin.coroutines.cancellation.CancellationException

/** TMDB's endpoints as a [MetadataProvider]. [TmdbHttp] sends the requests; `TmdbMappers.kt` reads the answers. */
class TmdbProvider(
    credentials: TmdbCredentials,
    timeouts: TmdbTimeouts = TmdbTimeouts(),
    engineFactory: () -> HttpClientEngine = ::createHttpClientEngine,
) : MetadataProvider {
    override val id: String = "tmdb"
    override val supportedIds: Set<String> = setOf("tmdbid", "imdbid")

    private val http = TmdbHttp(credentials, timeouts, engineFactory)

    /** One cheap call before a run, so wrong or revoked credentials fail once and clearly. */
    suspend fun verifyCredentials(): Result<Unit> = http.catching {
        http.get("authentication") { "verifying credentials" }
    }

    override suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>> = http.catching {
        val dto = http.getJson<TmdbSearchResponseDto>("search/multi", {
            parameter("query", title)
            parameter("include_adult", true)    // We need to include all possible movies/shows even if it's R+ rating
            parameter("page", 1)
        }) { "searching for '$title'" }
        dto.items.mapNotNull { it.toDomainModel(title) }
    }

    override suspend fun searchMovies(title: String, year: String): Result<List<MediaSearchResult>> = http.catching {
        // `year` matches a release in any country, not only the primary one, so a film whose TMDB
        // date is its premiere is still found by the year of a later release.
        val dto = http.getJson<TmdbMovieSearchResponseDto>("search/movie", {
            parameter("query", title)
            parameter("year", year)
            parameter("include_adult", true)
            parameter("page", 1)
        }) { "searching for '$title' ($year)" }
        dto.items.map { it.toDomainModel(title) }
    }

    /**
     * TMDB matches aliases server-side (e.g. romaji anime titles), but the search response only
     * carries the localized and original titles. For results that don't resemble the query by
     * either of those, pull the alternative titles so downstream scoring can see the alias: the
     * first [MatchScoring.MAX_ALT_TITLES_LOOKUPS] such results, in TMDB's order ([awaitingAliases]).
     *
     * Each record's aliases come through [cache], so a record that several searches find is fetched once; the lookup
     * still counts toward the limit, so a cached alias set reaches the results a fetch would have, and no others.
     *
     * A lookup for a record TMDB doesn't have (HTTP 404) leaves its result as it is. Any other failure
     * fails the search unless a result resembles the query anyway: the missing alias may be the match,
     * so "no match" would be a guess, and the file would be skipped and its show cached as a miss
     * instead of failing. One with no answer, or still rate-limited after its retries, also ends the
     * lookups, as the next ones would wait as long or be refused too; after another HTTP error the
     * next lookups go on, as one of them may find the alias.
     */
    override suspend fun withAlternativeTitles(
        results: List<MediaSearchResult>,
        title: String,
        cache: AliasCache,
    ): Result<List<MediaSearchResult>> = http.catching { results.lookUpAlternativeTitles(title, cache) }

    private suspend fun List<MediaSearchResult>.lookUpAlternativeTitles(title: String, cache: AliasCache): List<MediaSearchResult> {
        var lookups = 0
        var failed: Exception? = null
        var stopped = false
        val enriched = map { result ->
            if (stopped || result.matchConfidence >= MatchScoring.ALT_TITLES_CONFIDENCE_THRESHOLD || lookups >= MatchScoring.MAX_ALT_TITLES_LOOKUPS) {
                result
            } else {
                lookups++
                try {
                    result.withAliases(cache.getOrFetch(result) { alternativeTitles(result) }, title)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (failed == null) failed = e
                    if (e.endsLookups()) stopped = true
                    result
                }
            }
        }
        failed?.let { failure ->
            if (enriched.none { it.matchConfidence >= MatchScoring.ALT_TITLES_CONFIDENCE_THRESHOLD }) throw failure
        }
        return enriched
    }

    // A 404 is an answer: TMDB has no such record, so it has no aliases either.
    private suspend fun alternativeTitles(result: MediaSearchResult): AlternativeTitles {
        val endpoint = when (result) {
            is MediaSearchResult.Movie -> "movie/${result.tmdbId}/alternative_titles"
            is MediaSearchResult.TvShow -> "tv/${result.tmdbId}/alternative_titles"
        }
        val dto = try {
            http.getJson<TmdbAlternativeTitlesDto>(endpoint, optional = true) { "fetching alternative titles of '${result.title}'" }
        } catch (_: MetadataNotFoundException) {
            return AlternativeTitles.NONE
        }
        return AlternativeTitles(dto.all.map { it.title }, dto.seasonTitles())
    }

    private fun MediaSearchResult.withAliases(aliases: AlternativeTitles, queryTitle: String): MediaSearchResult {
        if (aliases.titles.isEmpty()) return this

        return when (this) {
            is MediaSearchResult.Movie -> copy(alternativeTitles = aliases.titles)
            is MediaSearchResult.TvShow -> copy(
                alternativeTitles = aliases.titles,
                // The show's own titles name it as a whole, whatever season a tag gives them.
                seasonTitles = aliases.seasonTitles - setOfNotNull(title, originalTitle),
            )
        }.let { enriched ->
            val confidence = enriched.allTitles.maxOf { calculateTitleConfidence(queryTitle, it) }
            when (enriched) {
                is MediaSearchResult.Movie -> enriched.copy(matchConfidence = confidence)
                is MediaSearchResult.TvShow -> enriched.copy(matchConfidence = confidence)
            }
        }
    }

    // One request brings the record with what a search result gets in extra calls: its alternative titles (and the
    // seasons they name) and its IMDb ID. An IMDb ID is first found among TMDB's movies or shows.
    override suspend fun movie(id: MediaId): Result<MediaSearchResult.Movie> = http.catching {
        val tmdbId = when (id.database) {
            MediaId.Database.TMDB -> id.id
            MediaId.Database.IMDB -> find(id).movies.firstOrNull()?.id ?: throw MetadataNotFoundException("No movie found for $id")
        }
        http.getJson<TmdbMovieDetailsDto>("movie/$tmdbId", { parameter("append_to_response", "alternative_titles") }) { "looking up movie $id" }
            .toDomainModel()
    }

    override suspend fun show(id: MediaId): Result<MediaSearchResult.TvShow> = http.catching {
        val tmdbId = when (id.database) {
            MediaId.Database.TMDB -> id.id
            MediaId.Database.IMDB -> find(id).shows.firstOrNull()?.id ?: throw MetadataNotFoundException("No show found for $id")
        }
        http.getJson<TmdbTvDetailsDto>("tv/$tmdbId", { parameter("append_to_response", "alternative_titles,external_ids") }) { "looking up show $id" }
            .toDomainModel()
    }

    private suspend fun find(id: MediaId): TmdbFindResponseDto =
        http.getJson("find/${id.id}", { parameter("external_source", "imdb_id") }) { "looking up $id" }

    override suspend fun season(
        show: CanonicalMedia.TvShow,
        season: Int,
    ): Result<CanonicalMedia.Season> = http.catching {
        requireNotNull(show.tmdbId) { "TMDb ID is required to fetch season details." }
        http.getJson<TmdbSeasonDto>("tv/${show.tmdbId}/season/$season") { "fetching season $season of '${show.title}'" }
            .toDomainModel(show)
    }

    override suspend fun episodeGroups(
        show: CanonicalMedia.TvShow,
    ): Result<List<CanonicalMedia.EpisodeGroup>> = http.catching {
        requireNotNull(show.tmdbId) { "TMDb ID is required to fetch episode groups." }
        val groups = http.getJson<TmdbEpisodeGroupsDto>("tv/${show.tmdbId}/episode_groups") { "fetching episode groups of '${show.title}'" }

        // Each group's episodes take one more call, so fetch only orderings that can stand in
        // for seasons, most trusted first, plus a couple of absolute orderings for absolute-numbered
        // releases ("One Piece - 1071"). Story-arc orderings are skipped.
        val seasonLikeTypes = EpisodeGroupMapper.SEASON_LIKE_TYPES
        val summaries = groups.results
            .mapNotNull { summary -> summary.type.toEpisodeGroupType()?.let { summary to it } }
        val seasonLike = summaries
            .filter { (_, type) -> type in seasonLikeTypes }
            .sortedBy { (_, type) -> seasonLikeTypes.indexOf(type) }
            .take(MAX_EPISODE_GROUP_LOOKUPS)
        val absolute = summaries
            .filter { (_, type) -> type == CanonicalMedia.EpisodeGroup.Type.ABSOLUTE }
            .take(MAX_ABSOLUTE_GROUP_LOOKUPS)
        (seasonLike + absolute)
            .map { (summary, type) ->
                http.getJson<TmdbEpisodeGroupDto>("tv/episode_group/${summary.id}") { "fetching episode group '${summary.name}' of '${show.title}'" }
                    .toDomainModel(show, type)
            }
    }

    override suspend fun externalIds(media: CanonicalMedia): Result<ExternalIds> = http.catching {
        // Search results carry no external IDs, so this is one extra call for the winning match.
        val (kind, tmdbId, title) = when (media) {
            is CanonicalMedia.Movie -> Triple("movie", media.tmdbId, media.title)
            is CanonicalMedia.TvShow -> Triple("tv", media.tmdbId, media.title)
            else -> throw UnsupportedOperationException("External IDs are looked up for movies and shows only.")
        }
        requireNotNull(tmdbId) { "TMDb ID is required to fetch external IDs." }
        http.getJson<TmdbExternalIdsDto>("$kind/$tmdbId/external_ids") { "fetching external IDs of '$title'" }
            .toDomainModel()
    }
}

// Cap extra API calls per show: popular shows can carry a dozen community-made groups.
private const val MAX_EPISODE_GROUP_LOOKUPS = 6
// Shows often carry an absolute order with and one without specials; both are fetched so they can agree.
private const val MAX_ABSOLUTE_GROUP_LOOKUPS = 2
