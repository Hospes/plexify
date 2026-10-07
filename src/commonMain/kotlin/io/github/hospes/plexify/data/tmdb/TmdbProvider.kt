package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.data.calculateTitleConfidence
import io.github.hospes.plexify.data.createHttpClientEngine
import io.github.hospes.plexify.data.nonstrict
import io.github.hospes.plexify.data.tmdb.dto.TmdbAlternativeTitlesDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbEpisodeDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbMediaItemDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbSearchResponseDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbSeasonDto
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.ktor.client.*
import io.ktor.client.call.*
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
) : MetadataProvider {
    override val id: String = "tmdb"
    override val supportedIds: Set<String> = setOf("tmdbid")

    private val httpClient by lazy {
        HttpClient(createHttpClientEngine()) {
            install(ContentNegotiation) { json(nonstrict) }

            // TMDB rate-limits bursts with 429 (and a Retry-After header). A built-in key is
            // shared by every user of a release, so give a busy moment a few tries.
            install(HttpRequestRetry) {
                retryIf(maxRetries = 3) { _, response -> response.status == HttpStatusCode.TooManyRequests }
                exponentialDelay()
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
    suspend fun verifyCredentials(): Result<Unit> = Result.runCatching {
        httpClient.get("authentication").ensureSuccess { "verifying credentials" }
    }

    override suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>> = Result.runCatching {
        val response = httpClient.get("search/multi") {
            parameter("query", title)
            parameter("include_adult", true)    // We need to include all possible movies/shows even if it's R+ rating
            parameter("page", 1)
        }
        response.ensureSuccess { "searching for '$title'" }
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
        val altTitles = runCatching {
            httpClient.get(endpoint).body<TmdbAlternativeTitlesDto>().all.map { it.title }
        }.getOrDefault(emptyList())
        if (altTitles.isEmpty()) return result

        return when (result) {
            is MediaSearchResult.Movie -> result.copy(alternativeTitles = altTitles)
            is MediaSearchResult.TvShow -> result.copy(alternativeTitles = altTitles)
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
    ): Result<CanonicalMedia.Episode> = Result.runCatching {
        requireNotNull(show.tmdbId) { "TMDb ID is required to fetch episode details." }
        val response = httpClient.get("tv/${show.tmdbId}/season/$season/episode/$episode")
        response.ensureSuccess { "fetching S${season}E${episode} of '${show.title}'" }
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
    ): Result<CanonicalMedia.Season> = Result.runCatching {
        requireNotNull(show.tmdbId) { "TMDb ID is required to fetch season details." }
        val response = httpClient.get("tv/${show.tmdbId}/season/$season")
        response.ensureSuccess { "fetching season $season of '${show.title}'" }
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

    private fun HttpResponse.ensureSuccess(action: () -> String) {
        when {
            status.isSuccess() -> Unit
            status == HttpStatusCode.Unauthorized -> throw TmdbCredentialsRejectedException(credentials.source)
            status == HttpStatusCode.TooManyRequests && credentials.source == TmdbCredentials.Source.BUILT_IN ->
                error("TMDB rate limit reached (HTTP 429) ${action()}. The built-in key is shared by all Plexify users; set your own with TMDB_API_ACCESS_TOKEN (see README).")

            else -> error("HTTP ${status.value} ${action()}")
        }
    }
}

// A result whose title (or original title) already resembles the query this closely
// doesn't need an alternative-titles lookup.
private const val ALT_TITLES_CONFIDENCE_THRESHOLD = 60.0

// Cap extra API calls per search: only the first few unconvincing results get an
// alternative-titles lookup. TMDB orders results by relevance, so the alias match
// (if any) is expected near the top.
private const val MAX_ALT_TITLES_LOOKUPS = 3

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