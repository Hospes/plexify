package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.MetadataNotFoundException
import io.github.hospes.plexify.domain.model.MediaId
import io.github.hospes.plexify.domain.model.MediaSearchResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Responses trimmed from live TMDB (October 2026).
class TmdbIdLookupTest {

    private val dotHack =
        """{"id":8864,"name":".hack","original_name":".hack","first_air_date":"2002-04-04",""" +
            """"alternative_titles":{"results":[{"iso_3166_1":"JP","title":".hack//SIGN","type":""},{"iso_3166_1":"US","title":".hack//Roots","type":"Season 2"}]},""" +
            """"external_ids":{"imdb_id":"tt0361140","tvdb_id":79099}}"""
    private val findDotHack =
        """{"movie_results":[],"person_results":[],"tv_results":[{"id":8864,"name":".hack","original_name":".hack","first_air_date":"2002-04-04","media_type":"tv"}],"tv_episode_results":[]}"""

    private fun provider(tmdb: TmdbResponder) = mockProvider(credentials = tokenCredentials, handler = tmdb.handler)

    @Test
    fun `a TMDB ID looks the movie up with its alternative titles and IMDb ID`() = runBlocking {
        val tmdb = TmdbResponder(
            mapOf(
                "/3/movie/603" to """{"id":603,"title":"The Matrix","original_title":"The Matrix","release_date":"1999-03-31","imdb_id":"tt0133093",""" +
                    """"alternative_titles":{"titles":[{"iso_3166_1":"GR","title":"Μάτριξ","type":""}]}}""",
            ),
        )
        val provider = provider(tmdb)

        val movie = provider.movie(MediaId(MediaId.Database.TMDB, "603")).getOrThrow()

        assertEquals(
            MediaSearchResult.Movie(
                title = "The Matrix", year = "1999", imdbId = "tt0133093", tmdbId = "603", provider = "TMDb",
                originalTitle = "The Matrix", alternativeTitles = listOf("Μάτριξ"),
            ),
            movie,
        )
        val url = tmdb.requests.single().url
        assertEquals("/3/movie/603", url.encodedPath)
        assertEquals("alternative_titles", url.parameters["append_to_response"])
    }

    @Test
    fun `a movie TMDB has no IMDb ID or date for has none`() = runBlocking {
        val provider = provider(
            TmdbResponder(mapOf("/3/movie/1721501" to """{"id":1721501,"title":"?","original_title":"\"?\"","release_date":"","imdb_id":null,"budget":228}""")),
        )

        val movie = provider.movie(MediaId(MediaId.Database.TMDB, "1721501")).getOrThrow()

        assertEquals(MediaSearchResult.Movie(title = "?", year = null, tmdbId = "1721501", provider = "TMDb", originalTitle = "\"?\""), movie)
    }

    @Test
    fun `a TMDB ID looks the show up with its season titles in one request`() = runBlocking {
        val tmdb = TmdbResponder(mapOf("/3/tv/8864" to dotHack))
        val provider = provider(tmdb)

        val show = provider.show(MediaId(MediaId.Database.TMDB, "8864")).getOrThrow()

        assertEquals("8864", show.tmdbId)
        assertEquals("2002", show.year)
        assertEquals("tt0361140", show.imdbId)
        assertEquals(listOf(".hack//SIGN", ".hack//Roots"), show.alternativeTitles)
        assertEquals(mapOf(".hack//Roots" to 2), show.seasonTitles)
        assertEquals("alternative_titles,external_ids", tmdb.requests.single().url.parameters["append_to_response"])
    }

    @Test
    fun `an IMDb ID is found among TMDB's shows`() = runBlocking {
        val tmdb = TmdbResponder(mapOf("/3/find/tt0361140" to findDotHack, "/3/tv/8864" to dotHack))
        val provider = provider(tmdb)

        val show = provider.show(MediaId(MediaId.Database.IMDB, "tt0361140")).getOrThrow()

        assertEquals("8864", show.tmdbId)
        assertEquals("tt0361140", show.imdbId)
        assertEquals(listOf("/3/find/tt0361140", "/3/tv/8864"), tmdb.paths)
        assertEquals("imdb_id", tmdb.requests.first().url.parameters["external_source"])
    }

    @Test
    fun `an IMDb ID of a show is no movie`() = runBlocking<Unit> {
        val provider = provider(TmdbResponder(mapOf("/3/find/tt0361140" to findDotHack)))

        val error = provider.movie(MediaId(MediaId.Database.IMDB, "tt0361140")).exceptionOrNull()

        assertIs<MetadataNotFoundException>(error)
    }

    @Test
    fun `an ID TMDB no longer has is not found`() = runBlocking<Unit> {
        val provider = provider(TmdbResponder())

        val error = provider.movie(MediaId(MediaId.Database.TMDB, "999999999")).exceptionOrNull()

        assertIs<MetadataNotFoundException>(error)
    }
}
