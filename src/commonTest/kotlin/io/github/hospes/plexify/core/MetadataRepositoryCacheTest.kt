package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.tmdb.TMDB_NOT_FOUND
import io.github.hospes.plexify.data.tmdb.TmdbTimeouts
import io.github.hospes.plexify.data.tmdb.capped
import io.github.hospes.plexify.data.tmdb.mockProvider
import io.github.hospes.plexify.data.tmdb.respondJson
import io.github.hospes.plexify.domain.matching.calculateTitleConfidence
import io.github.hospes.plexify.domain.model.MediaId
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [MetadataRepository] keeps for the run, through the real provider: what TMDB answered is asked once, a failed
 * request is asked again, and a cached answer is the one the request would give.
 */
class MetadataRepositoryCacheTest {

    private val requests = mutableListOf<String>()

    private fun repository(handler: MockRequestHandler): MetadataRepository {
        val provider = mockProvider(TmdbTimeouts(retryDelay = {})) { request ->
            requests += request.url.encodedPath + (request.url.parameters["query"]?.let { "?$it" } ?: "") +
                (request.url.parameters["year"]?.let { "&$it" } ?: "")
            handler(request)
        }
        return MetadataRepository(MetadataService(listOf(provider), NamingStrategy.Jellyfin))
    }

    private val ghosts = """{"results": [{"media_type": "tv", "id": 1, "name": "Ghosts", "original_name": "Ghosts", "first_air_date": "2021-10-07"}]}"""
    private val ghostsMovie = """{"results": [{"id": 2, "title": "Ghosts", "original_title": "Ghosts", "release_date": "2021-01-01"}]}"""

    @Test
    fun `a search is asked once per exact query and year`() = capped {
        val repository = repository { request ->
            if (request.url.encodedPath == "/3/search/movie") respondJson(ghostsMovie) else respondJson(ghosts)
        }
        with(LoggingContext()) {
            repeat(2) { assertEquals("Ghosts", repository.search("ghosts", null).getOrThrow().single().title) }
            repository.search("ghosts", "2021").getOrThrow()
            repository.search("Ghosts", null).getOrThrow()
            repeat(2) { repository.searchMovies("ghosts", "2021").getOrThrow() }
            repository.searchMovies("ghosts", "2019").getOrThrow()
        }
        // TMDB's general search ignores the year, but a search is only answered from the cache when it is the same.
        assertEquals(
            listOf(
                "/3/search/multi?ghosts", "/3/search/multi?ghosts", "/3/search/multi?Ghosts",
                "/3/search/movie?ghosts&2021", "/3/search/movie?ghosts&2019",
            ),
            requests,
        )
    }

    @Test
    fun `a failed search is asked again`() = capped {
        var failures = 1
        val repository = repository {
            if (failures-- > 0) respondJson("""{"success":false}""", HttpStatusCode.InternalServerError) else respondJson(ghosts)
        }
        with(LoggingContext()) {
            assertTrue(repository.search("ghosts", null).isFailure)
            repeat(2) { assertEquals("Ghosts", repository.search("ghosts", null).getOrThrow().single().title) }
        }
        assertEquals(listOf("/3/search/multi?ghosts", "/3/search/multi?ghosts"), requests)
    }

    // "Demon Slayer" resembles none of the arcs' queries, so each search looks its aliases up; a 404 for the film.
    private val demonSlayer = MediaSearchResult.TvShow("Demon Slayer: Kimetsu no Yaiba", "2019", tmdbId = "85937", provider = "TMDb")
    private val mugenTrain = MediaSearchResult.Movie("Demon Slayer -Kimetsu no Yaiba- The Movie: Mugen Train", "2020", tmdbId = "635302", provider = "TMDb")
    private val aliases = """{"id": 85937, "results": [
        {"iso_3166_1": "JP", "title": "Kimetsu no Yaiba", "type": "Romaji"},
        {"iso_3166_1": "JP", "title": "Kimetsu no Yaiba: Yuukaku-hen", "type": "Season 3 Romaji"}
    ]}"""

    private fun aliasHandler(failures: Int = 0): MockRequestHandler {
        var left = failures
        return { request ->
            when (request.url.encodedPath) {
                "/3/tv/85937/alternative_titles" ->
                    if (left-- > 0) respondJson("""{"success":false}""", HttpStatusCode.InternalServerError) else respondJson(aliases)

                else -> respondJson(TMDB_NOT_FOUND, HttpStatusCode.NotFound)
            }
        }
    }

    @Test
    fun `a record's aliases are fetched once and a 404 is kept as none`() = capped {
        val repository = repository(aliasHandler())
        val results = listOf(demonSlayer, mugenTrain)
        val (first, second) = with(LoggingContext()) {
            repository.withAlternativeTitles(results, "kimetsu no yaiba").getOrThrow() to
                repository.withAlternativeTitles(results, "kimetsu no yaiba yuukaku-hen").getOrThrow()
        }
        assertEquals(listOf("/3/tv/85937/alternative_titles", "/3/movie/635302/alternative_titles"), requests)

        // The cached aliases are what the lookup gave, scored for each search's own query.
        for (enriched in listOf(first, second)) {
            val show = enriched[0] as MediaSearchResult.TvShow
            assertEquals(listOf("Kimetsu no Yaiba", "Kimetsu no Yaiba: Yuukaku-hen"), show.alternativeTitles)
            assertEquals(mapOf("Kimetsu no Yaiba: Yuukaku-hen" to 3), show.seasonTitles)
            assertEquals(mugenTrain, enriched[1])
        }
        assertEquals(first[0].allTitles.maxOf { calculateTitleConfidence("kimetsu no yaiba", it) }, first[0].matchConfidence)
        assertEquals(second[0].allTitles.maxOf { calculateTitleConfidence("kimetsu no yaiba yuukaku-hen", it) }, second[0].matchConfidence)
    }

    @Test
    fun `a failed alias lookup is fetched again`() = capped {
        val repository = repository(aliasHandler(failures = 1))
        with(LoggingContext()) {
            assertTrue(repository.withAlternativeTitles(listOf(demonSlayer), "kimetsu no yaiba").isFailure)
            val show = repository.withAlternativeTitles(listOf(demonSlayer), "kimetsu no yaiba").getOrThrow().single()
            assertEquals(2, show.alternativeTitles.size)
        }
        assertEquals(listOf("/3/tv/85937/alternative_titles", "/3/tv/85937/alternative_titles"), requests)
    }

    @Test
    fun `cached aliases reach only the results a lookup would`() = capped {
        val repository = repository(aliasHandler())
        val named = demonSlayer.copy(matchConfidence = 100.0)
        val enriched = with(LoggingContext()) {
            repository.withAlternativeTitles(listOf(demonSlayer), "kimetsu no yaiba").getOrThrow()
            // Here the record resembles the query by its own title, so no lookup would give it aliases.
            repository.withAlternativeTitles(listOf(named), "demon slayer kimetsu no yaiba").getOrThrow().single()
        }
        assertEquals(named, enriched)
        assertEquals(listOf("/3/tv/85937/alternative_titles"), requests)
    }

    @Test
    fun `a cached alias set counts toward the lookups a search makes`() = capped {
        val repository = repository(aliasHandler())
        val others = (1..3).map { MediaSearchResult.TvShow("Unrelated $it", "2020", tmdbId = "$it", provider = "TMDb") }
        val enriched = with(LoggingContext()) {
            repository.withAlternativeTitles(listOf(demonSlayer), "kimetsu no yaiba").getOrThrow()
            repository.withAlternativeTitles(listOf(demonSlayer) + others, "kimetsu no yaiba").getOrThrow()
        }
        // Without the cache, the second search would look up the first three of its results (MAX_ALT_TITLES_LOOKUPS):
        // the cached record is one of them, so it fetches two more, and the fourth result stays as it is.
        assertEquals(
            listOf("/3/tv/85937/alternative_titles", "/3/tv/1/alternative_titles", "/3/tv/2/alternative_titles"),
            requests,
        )
        assertEquals(2, enriched[0].alternativeTitles.size)
        assertEquals(others[2], enriched[3])
    }

    private val inception = """{"id": 27205, "title": "Inception", "original_title": "Inception", "release_date": "2010-07-15", "imdb_id": "tt1375666", "alternative_titles": {"titles": []}}"""

    @Test
    fun `a record is looked up once per ID and a missing one is kept`() = capped {
        val repository = repository { request ->
            when (request.url.encodedPath) {
                "/3/find/tt1375666" -> respondJson("""{"movie_results": [{"id": 27205, "title": "Inception", "original_title": "Inception"}], "tv_results": []}""")
                "/3/movie/27205" -> respondJson(inception)
                else -> respondJson(TMDB_NOT_FOUND, HttpStatusCode.NotFound)
            }
        }
        with(LoggingContext()) {
            val imdb = MediaId(MediaId.Database.IMDB, "tt1375666")
            repeat(2) { assertEquals("27205", repository.findMovie(imdb).getOrThrow()?.tmdbId) }
            // Named by its ID as the title ("imdb-tt1375666"), it is the same record.
            assertEquals("27205", repository.findMovie(imdb.copy(fromTitle = true)).getOrThrow()?.tmdbId)
            // A show and a movie are looked up apart.
            assertNull(repository.findShow(imdb).getOrThrow())
            repeat(2) { assertNull(repository.findMovie(MediaId(MediaId.Database.TMDB, "1")).getOrThrow()) }
        }
        assertEquals(listOf("/3/find/tt1375666", "/3/movie/27205", "/3/find/tt1375666", "/3/movie/1"), requests)
    }

    @Test
    fun `a failed record lookup is asked again`() = capped {
        var failures = 1
        val repository = repository {
            if (failures-- > 0) respondJson("""{"success":false}""", HttpStatusCode.InternalServerError) else respondJson(inception)
        }
        with(LoggingContext()) {
            val id = MediaId(MediaId.Database.TMDB, "27205")
            assertTrue(repository.findMovie(id).isFailure)
            repeat(2) { assertEquals("Inception", repository.findMovie(id).getOrThrow()?.title) }
        }
        assertEquals(listOf("/3/movie/27205", "/3/movie/27205"), requests)
    }
}
