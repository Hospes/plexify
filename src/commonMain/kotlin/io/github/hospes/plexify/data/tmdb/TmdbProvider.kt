package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.MetadataNotFoundException
import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.data.MetadataTimeoutException
import io.github.hospes.plexify.data.calculateTitleConfidence
import io.github.hospes.plexify.data.createHttpClientEngine
import io.github.hospes.plexify.data.nonstrict
import io.github.hospes.plexify.data.tmdb.dto.TmdbAlternativeTitlesDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbEpisodeDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbEpisodeGroupDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbEpisodeGroupsDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbExternalIdsDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbMediaItemDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbSearchResponseDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbSeasonDto
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.service.EpisodeGroupMapper
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*

class TmdbProvider(
    private val credentials: TmdbCredentials,
    private val timeouts: TmdbTimeouts = TmdbTimeouts(),
    private val engineFactory: () -> HttpClientEngine = ::createHttpClientEngine,
) : MetadataProvider {
    override val id: String = "tmdb"
    override val supportedIds: Set<String> = setOf("tmdbid", "imdbid", "tvdbid")

    private val httpClient by lazy {
        HttpClient(engineFactory()) {
            install(ContentNegotiation) { json(nonstrict) }

            // TMDB rate-limits bursts with 429 (and a Retry-After header). A built-in key is
            // shared by every user of a release, so give a busy moment a few tries.
            install(HttpRequestRetry) {
                retryIf(maxRetries = 3) { _, response -> response.status == HttpStatusCode.TooManyRequests }
                // Fewer tries for a timeout (they share the count above): a stall usually means TMDB
                // or the network is down, and every try costs the full timeout.
                retryOnExceptionIf { _, cause -> cause.isTimeout() && retryCount <= timeouts.retries }
                exponentialDelay()
            }

            // Installed after HttpRequestRetry, so each try gets its own timeouts. The curl engine sets
            // the connect timeout on the handle (DNS included) but ignores a socket timeout; the request
            // timeout cancels the call from Ktor's side, body download included, so it is what catches
            // a server that stops sending.
            install(HttpTimeout) {
                connectTimeoutMillis = timeouts.connectMillis
                requestTimeoutMillis = timeouts.requestMillis
            }

//            install(Logging) {
//                logger = Logger.DEFAULT
//                level = LogLevel.ALL
//            }

            // The read access token goes in the header up front; the v3 API key only when
            // there is no token. Either one alone authenticates every v3 endpoint.
            defaultRequest {
                url {
                    takeFrom("https://api.themoviedb.org/3/")
                    if (credentials.accessToken == null) credentials.apiKey?.let { parameters.append("api_key", it) }
                }
                credentials.accessToken?.let { headers.append(HttpHeaders.Authorization, "Bearer $it") }
                headers.appendIfNameAbsent(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
        }
    }


    /** One cheap call before a run, so wrong or revoked credentials fail once and clearly. */
    suspend fun verifyCredentials(): Result<Unit> = tmdbCatching {
        get("authentication") { "verifying credentials" }
    }

    override suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>> = tmdbCatching {
        val response = get("search/multi", {
            parameter("query", title)
            parameter("include_adult", true)    // We need to include all possible movies/shows even if it's R+ rating
            parameter("page", 1)
        }) { "searching for '$title'" }
        val results = response.body<TmdbSearchResponseDto>().items.mapNotNull { it.toDomainModel(title) }

        // TMDB matches aliases server-side (e.g. romaji anime titles), but the search response only
        // carries the localized and original titles. For results that don't resemble the query by
        // either of those, pull the alternative titles so downstream scoring can see the alias.
        var lookups = 0
        results.map { result ->
            if (result.matchConfidence >= ALT_TITLES_CONFIDENCE_THRESHOLD || lookups >= MAX_ALT_TITLES_LOOKUPS) {
                result
            } else {
                lookups++
                enrichWithAlternativeTitles(result, title)
            }
        }
    }

    private suspend fun enrichWithAlternativeTitles(result: MediaSearchResult, queryTitle: String): MediaSearchResult {
        val endpoint = when (result) {
            is MediaSearchResult.Movie -> "movie/${result.tmdbId}/alternative_titles"
            is MediaSearchResult.TvShow -> "tv/${result.tmdbId}/alternative_titles"
        }
        val dto = runCatching {
            httpClient.get(endpoint).body<TmdbAlternativeTitlesDto>()
        }.getOrNull()
        val altTitles = dto?.all.orEmpty().map { it.title }
        if (dto == null || altTitles.isEmpty()) return result

        return when (result) {
            is MediaSearchResult.Movie -> result.copy(alternativeTitles = altTitles)
            is MediaSearchResult.TvShow -> result.copy(
                alternativeTitles = altTitles,
                // The show's own titles name it as a whole, whatever season a tag gives them.
                seasonTitles = dto.seasonTitles() - setOfNotNull(result.title, result.originalTitle),
            )
        }.let { enriched ->
            val confidence = enriched.allTitles.maxOf { calculateTitleConfidence(queryTitle, it) }
            when (enriched) {
                is MediaSearchResult.Movie -> enriched.copy(matchConfidence = confidence)
                is MediaSearchResult.TvShow -> enriched.copy(matchConfidence = confidence)
            }
        }
    }

    override suspend fun episode(
        show: CanonicalMedia.TvShow,
        season: Int,
        episode: Int
    ): Result<CanonicalMedia.Episode> = tmdbCatching {
        requireNotNull(show.tmdbId) { "TMDb ID is required to fetch episode details." }
        val response = get("tv/${show.tmdbId}/season/$season/episode/$episode") { "fetching S${season}E${episode} of '${show.title}'" }
        val dto = response.body<TmdbEpisodeDto>()

        CanonicalMedia.Episode(
            show = show,
            season = dto.seasonNumber,
            episode = dto.episodeNumber,
            title = dto.title,
        )
    }

    override suspend fun season(
        show: CanonicalMedia.TvShow,
        season: Int,
    ): Result<CanonicalMedia.Season> = tmdbCatching {
        requireNotNull(show.tmdbId) { "TMDb ID is required to fetch season details." }
        val response = get("tv/${show.tmdbId}/season/$season") { "fetching season $season of '${show.title}'" }
        val dto = response.body<TmdbSeasonDto>()

        CanonicalMedia.Season(
            show = show,
            seasonNumber = dto.seasonNumber,
            episodes = dto.episodes.map { ep ->
                CanonicalMedia.Episode(
                    show = show,
                    season = ep.seasonNumber,
                    episode = ep.episodeNumber,
                    title = ep.name,
                )
            },
        )
    }

    override suspend fun episodeGroups(
        show: CanonicalMedia.TvShow,
    ): Result<List<CanonicalMedia.EpisodeGroup>> = tmdbCatching {
        requireNotNull(show.tmdbId) { "TMDb ID is required to fetch episode groups." }
        val response = get("tv/${show.tmdbId}/episode_groups") { "fetching episode groups of '${show.title}'" }

        // Each group's episodes take one more call, so fetch only orderings that can stand in
        // for seasons, most trusted first, plus a couple of absolute orderings for absolute-numbered
        // releases ("One Piece - 1071"). Story-arc orderings are skipped.
        val seasonLikeTypes = EpisodeGroupMapper.SEASON_LIKE_TYPES
        val summaries = response.body<TmdbEpisodeGroupsDto>().results
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
                val groupResponse = get("tv/episode_group/${summary.id}") { "fetching episode group '${summary.name}' of '${show.title}'" }
                groupResponse.body<TmdbEpisodeGroupDto>().toDomainModel(show, type)
            }
    }

    override suspend fun externalIds(media: CanonicalMedia): Result<ExternalIds> = tmdbCatching {
        // Search results carry no external IDs, so this is one extra call for the winning match.
        val (kind, tmdbId, title) = when (media) {
            is CanonicalMedia.Movie -> Triple("movie", media.tmdbId, media.title)
            is CanonicalMedia.TvShow -> Triple("tv", media.tmdbId, media.title)
            else -> throw UnsupportedOperationException("External IDs are looked up for movies and shows only.")
        }
        requireNotNull(tmdbId) { "TMDb ID is required to fetch external IDs." }
        val response = get("$kind/$tmdbId/external_ids") { "fetching external IDs of '$title'" }
        val dto = response.body<TmdbExternalIdsDto>()
        ExternalIds(
            imdbId = dto.imdbId?.ifBlank { null },
            tvdbId = dto.tvdbId?.takeIf { it > 0 }?.toString(),
        )
    }

    // Every result leaves through here, so no caller can print a credential from an error message.
    private inline fun <T> tmdbCatching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: Throwable) {
            Result.failure(credentials.redact(e))
        }

    /**
     * GET [path] and check the status. A timeout fails with a short message naming the [action]
     * instead of Ktor's, which spells out the whole request URL.
     */
    private suspend fun get(path: String, block: HttpRequestBuilder.() -> Unit = {}, action: () -> String): HttpResponse {
        val response = try {
            httpClient.get(path, block)
        } catch (e: Throwable) {
            if (e.isTimeout()) throw MetadataTimeoutException("TMDB request timed out ${action()}")
            throw e
        }
        response.ensureSuccess(action)
        return response
    }

    private fun HttpResponse.ensureSuccess(action: () -> String) {
        when {
            status.isSuccess() -> Unit
            status == HttpStatusCode.Unauthorized -> throw TmdbCredentialsRejectedException(credentials.source)
            status == HttpStatusCode.NotFound -> throw MetadataNotFoundException("HTTP 404 ${action()}")
            status == HttpStatusCode.TooManyRequests && credentials.source == TmdbCredentials.Source.BUILT_IN ->
                error("TMDB rate limit reached (HTTP 429) ${action()}. The built-in key is shared by all Plexify users; set your own with TMDB_API_ACCESS_TOKEN (see README).")

            else -> error("HTTP ${status.value} ${action()}")
        }
    }
}

/**
 * Limits for one TMDB call. Files are processed one after another, so without them a stalled
 * connection would block the whole run.
 */
data class TmdbTimeouts(
    val connectMillis: Long = 10_000,
    val requestMillis: Long = 30_000,
    /** Extra tries after a timeout, out of the three retries a call gets (HTTP 429 may use all three). */
    val retries: Int = 1,
)

// Ktor may deliver a timeout wrapped in a CancellationException.
private fun Throwable.isTimeout(): Boolean = generateSequence(this) { it.cause }.take(8).any {
    it is HttpRequestTimeoutException || it is ConnectTimeoutException || it is SocketTimeoutException
}

// A result whose title (or original title) already resembles the query this closely
// doesn't need an alternative-titles lookup.
private const val ALT_TITLES_CONFIDENCE_THRESHOLD = 60.0

// Cap extra API calls per search: only the first few unconvincing results get an
// alternative-titles lookup. TMDB orders results by relevance, so the alias match
// (if any) is expected near the top.
private const val MAX_ALT_TITLES_LOOKUPS = 3

// Cap extra API calls per show: popular shows can carry a dozen community-made groups.
private const val MAX_EPISODE_GROUP_LOOKUPS = 6
// Shows often carry an absolute order with and one without specials; both are fetched so they can agree.
private const val MAX_ABSOLUTE_GROUP_LOOKUPS = 2

// TMDB numbers the types 1..7 in this order.
private fun Int.toEpisodeGroupType(): CanonicalMedia.EpisodeGroup.Type? =
    CanonicalMedia.EpisodeGroup.Type.entries.getOrNull(this - 1)

private fun TmdbEpisodeGroupDto.toDomainModel(
    show: CanonicalMedia.TvShow,
    type: CanonicalMedia.EpisodeGroup.Type,
) = CanonicalMedia.EpisodeGroup(
    name = name,
    type = type,
    parts = groups.map { part ->
        CanonicalMedia.EpisodeGroup.Part(
            name = part.name,
            order = part.order,
            episodes = part.episodes.sortedBy { it.order }.map { ep ->
                CanonicalMedia.Episode(
                    show = show,
                    season = ep.seasonNumber,
                    episode = ep.episodeNumber,
                    title = ep.name,
                )
            },
        )
    },
)

private fun TmdbMediaItemDto.toDomainModel(queryTitle: String): MediaSearchResult? {
    return when (this) {
        is TmdbMediaItemDto.Movie -> MediaSearchResult.Movie(
            title = title,
            //year = releaseDate?.year?.toString(),
            year = releaseDate?.substringBefore("-")?.ifBlank { null }, // Extract year from "YYYY-MM-DD"
            tmdbId = id,
            provider = "TMDb",
            matchConfidence = maxOf(
                calculateTitleConfidence(queryTitle, title),
                calculateTitleConfidence(queryTitle, originalTitle),
            ),
            originalTitle = originalTitle,
        )

        is TmdbMediaItemDto.TvShow -> MediaSearchResult.TvShow(
            title = title,
            //year = firstAirDate?.year?.toString(),//releaseDate?.substringBefore("-"), // Extract year from "YYYY-MM-DD"
            year = firstAirDate?.substringBefore("-")?.ifBlank { null }, // Extract year from "YYYY-MM-DD"
            tmdbId = id,
            provider = "TMDb",
            matchConfidence = maxOf(
                calculateTitleConfidence(queryTitle, title),
                calculateTitleConfidence(queryTitle, originalTitle),
            ),
            originalTitle = originalTitle,
        )

        else -> null
    }
}