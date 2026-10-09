package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TmdbMovieSearchTest {

    private val credentials = TmdbCredentials(apiKey = null, accessToken = "token", source = TmdbCredentials.Source.USER)
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun `search with a year reads movie results that carry no media type`() = runBlocking {
        val requests = mutableListOf<Url>()
        val provider = TmdbProvider(credentials) {
            MockEngine { request ->
                requests += request.url
                // Trimmed from a live search/movie response: unlike search/multi, no "media_type".
                respond(
                    """{"page":1,"results":[{"adult":false,"id":86889,"original_title":"Dracula","title":"Dracula","release_date":"1974-02-08","popularity":3.1}],"total_results":1}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            }
        }

        val results = provider.searchMovies("dracula", "1974").getOrThrow()

        val movie = assertIs<MediaSearchResult.Movie>(results.single())
        assertEquals("86889", movie.tmdbId)
        assertEquals("1974", movie.year)
        val url = requests.single()
        assertEquals("/3/search/movie", url.encodedPath)
        assertEquals("dracula", url.parameters["query"])
        assertEquals("1974", url.parameters["year"])
    }
}
