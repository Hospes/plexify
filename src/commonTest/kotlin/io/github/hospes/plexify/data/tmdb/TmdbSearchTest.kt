package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `search/multi` and the alternative-titles lookups it makes for results that don't resemble the query. Bodies are
 * hand-written in the shape of TMDB's (October 2026). The failure rule for a lookup with no answer is in
 * TmdbUnreachableTest. A lookup that fails with an HTTP error other than 404 fails the search by the same rule, but
 * only no answer and HTTP 429 (after its retries) end the lookups.
 */
class TmdbSearchTest {

    private fun tv(id: Int, name: String, originalName: String = name) =
        """{"adult":false,"id":$id,"name":"$name","original_name":"$originalName","media_type":"tv","original_language":"en","first_air_date":"2000-10-03","popularity":12.3}"""

    private fun movie(id: Int, title: String) =
        """{"adult":false,"id":$id,"title":"$title","original_title":"$title","media_type":"movie","original_language":"en","release_date":"2005-06-10","popularity":4.5}"""

    private fun results(vararg items: String) = """{"page":1,"results":[${items.joinToString(",")}],"total_pages":1,"total_results":${items.size}}"""

    private fun tvAliases(id: Int, vararg titles: Pair<String, String>) =
        "/3/tv/$id/alternative_titles" to """{"id":$id,"results":[${titles.joinToString(",") { (title, type) -> """{"iso_3166_1":"JP","title":"$title","type":"$type"}""" }}]}"""

    private fun noTvAliases(vararg ids: Int) = ids.map { tvAliases(it) }

    private fun TmdbResponder.aliasLookups() = paths.filter { it.endsWith("/alternative_titles") }

    private fun provider(tmdb: TmdbResponder) = mockProvider(credentials = tokenCredentials, handler = tmdb.handler)

    @Test
    fun `a result that resembles the query by 60 percent gets no alias lookup and one below gets one`() = runBlocking {
        // Against "Dark Angel": "Dark Water" is 4 edits in 10 (60), "Dark Waters" 5 in 11 (54.5).
        val tmdb = TmdbResponder(mapOf("/3/search/multi" to results(tv(1, "Dark Water"), tv(2, "Dark Waters"))) + noTvAliases(2))

        provider(tmdb).search("Dark Angel", null).getOrThrow()

        assertEquals(listOf("/3/tv/2/alternative_titles"), tmdb.aliasLookups())
        assertEquals(emptyList(), tmdb.unanswered)
    }

    @Test
    fun `an original title that resembles the query is enough`() = runBlocking {
        val tmdb = TmdbResponder(mapOf("/3/search/multi" to results(tv(1, "Ангел тьмы", originalName = "Dark Angel"))))

        provider(tmdb).search("Dark Angel", null).getOrThrow()

        assertEquals(emptyList(), tmdb.aliasLookups())
    }

    @Test
    fun `only the first three results that don't resemble the query get an alias lookup`() = runBlocking {
        val tmdb = TmdbResponder(
            mapOf(
                "/3/search/multi" to results(
                    movie(10, "Shadow Wings"), tv(11, "Dark Angel"), tv(12, "Black Seraph"), movie(13, "Night Watcher"), tv(14, "Fallen"),
                ),
                "/3/movie/10/alternative_titles" to """{"id":10,"titles":[]}""",
                "/3/movie/13/alternative_titles" to """{"id":13,"titles":[]}""",
            ) + noTvAliases(12),
        )

        val found = provider(tmdb).search("Dark Angel", null).getOrThrow()

        assertEquals(5, found.size)
        assertEquals(
            listOf("/3/movie/10/alternative_titles", "/3/tv/12/alternative_titles", "/3/movie/13/alternative_titles"),
            tmdb.aliasLookups(),
        )
    }

    @Test
    fun `a show's aliases make it resemble the query and name its seasons`() = runBlocking {
        val tmdb = TmdbResponder(
            mapOf(
                "/3/search/multi" to results(tv(85937, "Demon Slayer: Kimetsu no Yaiba", originalName = "鬼滅の刃")),
                tvAliases(
                    85937,
                    "Kimetsu no Yaiba" to "Romaji",
                    "Kimetsu no Yaiba: Yuukaku-hen" to "Season 3 Romaji",
                    "鬼滅の刃" to "Season 1",
                ),
            ),
        )

        val show = assertIs<MediaSearchResult.TvShow>(provider(tmdb).search("Kimetsu no Yaiba", null).getOrThrow().single())

        assertEquals(100.0, show.matchConfidence)
        assertEquals(listOf("Kimetsu no Yaiba", "Kimetsu no Yaiba: Yuukaku-hen", "鬼滅の刃"), show.alternativeTitles)
        // The show's own original title names it as a whole, whatever season its tag gives it.
        assertEquals(mapOf("Kimetsu no Yaiba: Yuukaku-hen" to 3), show.seasonTitles)
    }

    @Test
    fun `a movie's aliases are read too`() = runBlocking {
        val tmdb = TmdbResponder(
            mapOf(
                "/3/search/multi" to results(movie(129, "Spirited Away")),
                "/3/movie/129/alternative_titles" to """{"id":129,"titles":[{"iso_3166_1":"JP","title":"Sen to Chihiro no Kamikakushi","type":"Romaji"}]}""",
            ),
        )

        val movie = assertIs<MediaSearchResult.Movie>(provider(tmdb).search("Sen to Chihiro no Kamikakushi", null).getOrThrow().single())

        assertEquals(100.0, movie.matchConfidence)
        assertEquals(listOf("Sen to Chihiro no Kamikakushi"), movie.alternativeTitles)
    }

    @Test
    fun `a movie search with a year looks up aliases too`() = runBlocking {
        val tmdb = TmdbResponder(
            mapOf(
                "/3/search/movie" to """{"page":1,"results":[{"adult":false,"id":129,"title":"Spirited Away","original_title":"千と千尋の神隠し","release_date":"2001-07-20"}],"total_results":1}""",
                "/3/movie/129/alternative_titles" to """{"id":129,"titles":[{"iso_3166_1":"JP","title":"Sen to Chihiro no Kamikakushi","type":"Romaji"}]}""",
            ),
        )

        val movie = provider(tmdb).searchMovies("Sen to Chihiro no Kamikakushi", "2001").getOrThrow().single()

        assertEquals(listOf("Sen to Chihiro no Kamikakushi"), movie.alternativeTitles)
    }

    @Test
    fun `an alias lookup for a record TMDB doesn't have leaves its result as it is and the next ones go on`() = runBlocking {
        // TMDB answers the first lookup with HTTP 404 and its not-found body.
        val tmdb = TmdbResponder(
            mapOf("/3/search/multi" to results(tv(1, "Black Seraph"), tv(2, "Shadow Wings")), tvAliases(2, "Dark Angel" to "")),
            notFound = setOf("/3/tv/1/alternative_titles"),
        )

        val (first, second) = provider(tmdb).search("Dark Angel", null).getOrThrow().map { assertIs<MediaSearchResult.TvShow>(it) }

        assertEquals(emptyList(), first.alternativeTitles)
        assertEquals(listOf("Dark Angel"), second.alternativeTitles)
        assertEquals(listOf("/3/tv/1/alternative_titles", "/3/tv/2/alternative_titles"), tmdb.aliasLookups())
    }

    /** [tmdb], except that the alias lookup of [path] fails with [status] and TMDB's [body] for it. */
    private fun failingLookup(tmdb: TmdbResponder, path: String, status: HttpStatusCode, body: String, credentials: TmdbCredentials = tokenCredentials) =
        mockProvider(TmdbTimeouts(retryDelay = {}), credentials) { request ->
            if (request.url.encodedPath == path) {
                tmdb.handler(this, request) // recorded with the others
                respondJson(body, status)
            } else {
                tmdb.handler(this, request)
            }
        }

    private val serverError = """{"success":false,"status_code":11,"status_message":"Internal error: Something went wrong, contact TMDB."}"""
    private val rateLimited = """{"success":false,"status_code":25,"status_message":"Your request count (41) is over the allowed limit of (40)."}"""

    @Test
    fun `an alias lookup that fails with an HTTP error fails a search with no result that resembles the query`() = runBlocking {
        // The issue #136 probe: one weak result, whose lookup gets HTTP 500. The alias may be the match.
        val tmdb = TmdbResponder(mapOf("/3/search/multi" to results(tv(1, "Black Seraph"), tv(2, "Shadow Wings"))) + noTvAliases(2))
        val provider = failingLookup(tmdb, "/3/tv/1/alternative_titles", HttpStatusCode.InternalServerError, serverError)

        val error = provider.search("Dark Angel", null).exceptionOrNull()

        assertEquals("HTTP 500 fetching alternative titles of 'Black Seraph'", error?.message)
        // The next lookup goes on, as it may find the alias; it doesn't.
        assertEquals(listOf("/3/tv/1/alternative_titles", "/3/tv/2/alternative_titles"), tmdb.aliasLookups())
    }

    @Test
    fun `an alias found by a lookup after one that fails with an HTTP error keeps the search`() = runBlocking {
        val tmdb = TmdbResponder(mapOf("/3/search/multi" to results(tv(1, "Black Seraph"), tv(2, "Shadow Wings")), tvAliases(2, "Dark Angel" to "")))
        val provider = failingLookup(tmdb, "/3/tv/1/alternative_titles", HttpStatusCode.InternalServerError, serverError)

        val (first, second) = provider.search("Dark Angel", null).getOrThrow().map { assertIs<MediaSearchResult.TvShow>(it) }

        assertEquals(emptyList(), first.alternativeTitles)
        assertEquals(listOf("Dark Angel"), second.alternativeTitles)
        assertEquals(100.0, second.matchConfidence)
    }

    @Test
    fun `an alias lookup the built-in key's rate limit still refuses after the retries fails the search and ends the lookups`() = runBlocking {
        val builtIn = TmdbCredentials(apiKey = null, accessToken = "built-in-token", source = TmdbCredentials.Source.BUILT_IN)
        val tmdb = TmdbResponder(mapOf("/3/search/multi" to results(tv(1, "Black Seraph"), tv(2, "Shadow Wings"))) + noTvAliases(2))
        val provider = failingLookup(tmdb, "/3/tv/1/alternative_titles", HttpStatusCode.TooManyRequests, rateLimited, builtIn)

        val error = provider.search("Dark Angel", null).exceptionOrNull()

        assertEquals(
            "TMDB rate limit reached (HTTP 429) fetching alternative titles of 'Black Seraph'. The built-in key is shared by all " +
                "Plexify users; set your own with TMDB_API_ACCESS_TOKEN (see README).",
            error?.message,
        )
        // The first try and its three retries; the next lookup would be refused too, so it isn't sent.
        assertEquals(List(4) { "/3/tv/1/alternative_titles" }, tmdb.aliasLookups())
    }

    @Test
    fun `an alias lookup that fails with an HTTP error leaves a search with a result that resembles the query as it is`() = runBlocking {
        val tmdb = TmdbResponder(mapOf("/3/search/multi" to results(tv(1, "Black Seraph"), tv(2, "Dark Angel"))))
        val provider = failingLookup(tmdb, "/3/tv/1/alternative_titles", HttpStatusCode.InternalServerError, serverError)

        val found = provider.search("Dark Angel", null).getOrThrow()

        assertEquals(listOf("1", "2"), found.map { it.tmdbId })
        assertEquals(emptyList(), assertIs<MediaSearchResult.TvShow>(found.first()).alternativeTitles)
    }

    @Test
    fun `an alias found by an earlier lookup keeps the search when a later lookup gets no answer`() = runBlocking {
        val tmdb = TmdbResponder(
            mapOf("/3/search/multi" to results(tv(1, "Shadow Wings"), tv(2, "Black Seraph")), tvAliases(1, "Dark Angel" to "")),
        )
        val provider = mockProvider(credentials = tokenCredentials) { request ->
            if (request.url.encodedPath == "/3/tv/2/alternative_titles") {
                throw IllegalStateException("Connection failed for request: <request>. Reason: Could not connect to server")
            }
            tmdb.handler(this, request)
        }

        val (first, second) = provider.search("Dark Angel", null).getOrThrow()

        assertEquals(100.0, first.matchConfidence)
        assertEquals(emptyList(), assertIs<MediaSearchResult.TvShow>(second).alternativeTitles)
    }

    @Test
    fun `people in a multi search are left out`() = runBlocking {
        val tmdb = TmdbResponder(
            mapOf(
                "/3/search/multi" to results(
                    """{"adult":false,"id":1100,"name":"Jessica Alba","original_name":"Jessica Alba","media_type":"person","popularity":9.8,"known_for_department":"Acting"}""",
                    tv(1, "Dark Angel"),
                ),
            ),
        )

        val found = provider(tmdb).search("Dark Angel", null).getOrThrow()

        assertEquals(listOf("1"), found.map { it.tmdbId })
    }
}
