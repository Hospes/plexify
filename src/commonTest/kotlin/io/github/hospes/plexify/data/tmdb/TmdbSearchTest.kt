package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.domain.model.MediaSearchResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `search/multi` and the alternative-titles lookups it makes for results that don't resemble the query. Bodies are
 * hand-written in the shape of TMDB's (October 2026). The failure rule for a lookup with no answer is in
 * TmdbUnreachableTest.
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
