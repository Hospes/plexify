package io.github.hospes.plexify.data.imdb

import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.data.calculateTitleConfidence
import io.github.hospes.plexify.data.createHttpClientEngine
import io.github.hospes.plexify.data.imdb.dto.ImdbMediaItemDto
import io.github.hospes.plexify.data.imdb.dto.ImdbSearchResponseDto
import io.github.hospes.plexify.data.nonstrict
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*

// [ Base URL: api.imdbapi.dev ] -> https://imdbapi.dev/
object ImdbProvider : MetadataProvider {

    private val httpClient by lazy {
        HttpClient(createHttpClientEngine()) {
            install(ContentNegotiation) { json(nonstrict) }

            defaultRequest {
                url("https://api.imdbapi.dev/")
                headers.appendIfNameAbsent(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
        }
    }


    override suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>> = Result.runCatching {
        httpClient.get("search/titles") {
            parameter("query", title)
            parameter("limit", 5)
        }.body<ImdbSearchResponseDto>().items.mapNotNull { it.toDomainModel(title) }
    }

    override suspend fun episode(
        show: CanonicalMedia.TvShow,
        season: Int,
        episode: Int
    ): Result<CanonicalMedia.Episode> {
        return Result.failure(UnsupportedOperationException("IMDb provider does not support fetching episode details."))
    }
}

private fun ImdbMediaItemDto.toDomainModel(queryTitle: String): MediaSearchResult? {
    val confidence = calculateTitleConfidence(queryTitle, this.title)
    return when (this) {
        is ImdbMediaItemDto.Movie -> MediaSearchResult.Movie(
            title = title,
            year = startYear.toString(),
            imdbId = id,
            provider = "IMDb",
            matchConfidence = confidence,
        )

        is ImdbMediaItemDto.TvShow -> MediaSearchResult.TvShow(
            title = title,
            year = startYear.toString(),
            imdbId = id,
            provider = "IMDb",
            matchConfidence = confidence,
        )

        is ImdbMediaItemDto.TvMiniShow -> MediaSearchResult.TvShow(
            title = title,
            year = startYear.toString(),
            imdbId = id,
            provider = "IMDb",
            matchConfidence = confidence,
        )

        else -> null
    }
}