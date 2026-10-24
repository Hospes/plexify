package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** `movie/{id}/external_ids` and `tv/{id}/external_ids`, which the Plex template needs for its IMDb tag. */
class TmdbExternalIdsTest {

    private val matrix = CanonicalMedia.Movie(title = "The Matrix", year = 1999, tmdbId = "603")
    private val breakingBad = CanonicalMedia.TvShow(title = "Breaking Bad", year = 2008, tmdbId = "1396")

    // Hand-written in the shape of TMDB's bodies (October 2026). [imdbId] is the JSON value: a quoted ID, "" or null.
    private fun externalIds(id: Int, imdbId: String) =
        """{"id":$id,"imdb_id":$imdbId,"wikidata_id":null,"facebook_id":null,"instagram_id":null,"twitter_id":null}"""

    private fun provider(tmdb: TmdbResponder) = mockProvider(credentials = tokenCredentials, handler = tmdb.handler)

    @Test
    fun `a movie's and a show's IMDb IDs are read from their own endpoints`() = runBlocking {
        val tmdb = TmdbResponder(
            mapOf(
                "/3/movie/603/external_ids" to externalIds(603, "\"tt0133093\""),
                "/3/tv/1396/external_ids" to externalIds(1396, "\"tt0903747\""),
            ),
        )
        val provider = provider(tmdb)

        assertEquals(ExternalIds(imdbId = "tt0133093"), provider.externalIds(matrix).getOrThrow())
        assertEquals(ExternalIds(imdbId = "tt0903747"), provider.externalIds(breakingBad).getOrThrow())
        assertEquals(listOf("/3/movie/603/external_ids", "/3/tv/1396/external_ids"), tmdb.paths)
    }

    @Test
    fun `a null or empty IMDb ID is none`() = runBlocking {
        val provider = provider(
            TmdbResponder(
                mapOf(
                    "/3/movie/603/external_ids" to externalIds(603, "null"),
                    "/3/tv/1396/external_ids" to externalIds(1396, "\"\""),
                ),
            ),
        )

        assertEquals(ExternalIds(imdbId = null), provider.externalIds(matrix).getOrThrow())
        assertEquals(ExternalIds(imdbId = null), provider.externalIds(breakingBad).getOrThrow())
    }

    @Test
    fun `a record TMDB doesn't have has no IMDb ID and a server error fails the lookup`() = runBlocking {
        val provider = mockProvider(credentials = tokenCredentials) { request ->
            if (request.url.encodedPath.startsWith("/3/movie/")) respondJson(TMDB_NOT_FOUND, HttpStatusCode.NotFound)
            else respondJson("""{"status_code":11,"status_message":"Internal error."}""", HttpStatusCode.InternalServerError)
        }
        val service = MetadataService(listOf(provider), NamingStrategy.Plex)

        with(LoggingContext()) {
            assertEquals(Result.success(null), service.getExternalIds(matrix))
            assertEquals("HTTP 500 fetching external IDs of 'Breaking Bad'", service.getExternalIds(breakingBad).exceptionOrNull()?.message)
        }
    }
}
