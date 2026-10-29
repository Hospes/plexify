package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.tmdb.TMDB_NOT_FOUND
import io.github.hospes.plexify.data.tmdb.TmdbTimeouts
import io.github.hospes.plexify.data.tmdb.capped
import io.github.hospes.plexify.data.tmdb.mockProvider
import io.github.hospes.plexify.data.tmdb.respondJson
import io.github.hospes.plexify.domain.matching.calculateTitleConfidence
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaId
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.awaitCancellation
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

    private fun repository(
        template: NamingStrategy = NamingStrategy.Jellyfin,
        timeouts: TmdbTimeouts = TmdbTimeouts(retryDelay = {}),
        handler: MockRequestHandler,
    ): MetadataRepository {
        val provider = mockProvider(timeouts) { request ->
            requests += request.url.encodedPath + (request.url.parameters["query"]?.let { "?$it" } ?: "") +
                (request.url.parameters["year"]?.let { "&$it" } ?: "")
            handler(request)
        }
        return MetadataRepository(MetadataService(listOf(provider), template))
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
        val repository = repository(handler = aliasHandler())
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
        val repository = repository(handler = aliasHandler(failures = 1))
        with(LoggingContext()) {
            assertTrue(repository.withAlternativeTitles(listOf(demonSlayer), "kimetsu no yaiba").isFailure)
            val show = repository.withAlternativeTitles(listOf(demonSlayer), "kimetsu no yaiba").getOrThrow().single()
            assertEquals(2, show.alternativeTitles.size)
        }
        assertEquals(listOf("/3/tv/85937/alternative_titles", "/3/tv/85937/alternative_titles"), requests)
    }

    @Test
    fun `cached aliases reach only the results a lookup would`() = capped {
        val repository = repository(handler = aliasHandler())
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
        val repository = repository(handler = aliasHandler())
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

    // With the Plex template, which names the IMDb ID, the alias lookup fetches the record's details with the aliases
    // appended (and a show's external IDs): what movie() and show() ask for.
    private fun showDetails(imdbId: String?) = """{"id": 85937, "name": "Demon Slayer: Kimetsu no Yaiba", "first_air_date": "2019-04-06",
        "external_ids": {"imdb_id": ${imdbId?.let { "\"$it\"" }}}, "alternative_titles": $aliases}"""
    private fun movieDetails(imdbId: String?) = """{"id": 635302, "title": "${mugenTrain.title}", "release_date": "2020-10-16",
        "imdb_id": ${imdbId?.let { "\"$it\"" }}, "alternative_titles": {"titles": [{"iso_3166_1": "JP", "title": "Mugen Ressha-hen", "type": ""}]}}"""

    private val demonSlayerMatch = CanonicalMedia.TvShow("Demon Slayer: Kimetsu no Yaiba", 2019, tmdbId = "85937")
    private val mugenTrainMatch = CanonicalMedia.Movie(mugenTrain.title, 2020, tmdbId = "635302")

    /** Answers the Plex alias lookups with [show] and [movie] (null: 404), and `external_ids` with tt + the TMDB ID. */
    private fun plexHandler(show: String?, movie: String?): MockRequestHandler = { request ->
        val append = request.url.parameters["append_to_response"]
        when (request.url.encodedPath) {
            "/3/tv/85937" -> if (append == "alternative_titles,external_ids" && show != null) respondJson(show) else respondJson(TMDB_NOT_FOUND, HttpStatusCode.NotFound)
            "/3/movie/635302" -> if (append == "alternative_titles" && movie != null) respondJson(movie) else respondJson(TMDB_NOT_FOUND, HttpStatusCode.NotFound)
            "/3/tv/85937/external_ids" -> respondJson("""{"id": 85937, "imdb_id": "tt85937"}""")
            "/3/movie/635302/external_ids" -> respondJson("""{"id": 635302, "imdb_id": "tt635302"}""")
            else -> respondJson(TMDB_NOT_FOUND, HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `with Plex an alias lookup brings the IMDb ID along`() = capped {
        val repository = repository(NamingStrategy.Plex, handler = plexHandler(showDetails("tt9335498"), movieDetails("tt11032374")))
        val (show, movie) = with(LoggingContext()) {
            val enriched = repository.withAlternativeTitles(listOf(demonSlayer, mugenTrain), "kimetsu no yaiba").getOrThrow()
            assertEquals(listOf("Kimetsu no Yaiba", "Kimetsu no Yaiba: Yuukaku-hen"), enriched[0].alternativeTitles)
            assertEquals(mapOf("Kimetsu no Yaiba: Yuukaku-hen" to 3), (enriched[0] as MediaSearchResult.TvShow).seasonTitles)
            assertEquals(listOf("Mugen Ressha-hen"), enriched[1].alternativeTitles)
            repository.withImdbId(demonSlayerMatch).getOrThrow() to repository.withImdbId(mugenTrainMatch).getOrThrow()
        }
        assertEquals("tt9335498", show.imdbId)
        assertEquals("tt11032374", movie.imdbId)
        // No external_ids call: the lookups brought the IDs.
        assertEquals(listOf("/3/tv/85937", "/3/movie/635302"), requests)
    }

    @Test
    fun `with Plex a record the alias lookup has no IMDb ID for is kept without one`() = capped {
        val repository = repository(NamingStrategy.Plex, handler = plexHandler(showDetails(null), movieDetails(null)))
        val (show, movie) = with(LoggingContext()) {
            repository.withAlternativeTitles(listOf(demonSlayer, mugenTrain), "kimetsu no yaiba").getOrThrow()
            repository.withImdbId(demonSlayerMatch).getOrThrow() to repository.withImdbId(mugenTrainMatch).getOrThrow()
        }
        // As an external_ids lookup answering null leaves them: TMDB has none.
        assertEquals(demonSlayerMatch, show)
        assertEquals(mugenTrainMatch, movie)
        assertEquals(listOf("/3/tv/85937", "/3/movie/635302"), requests)
    }

    @Test
    fun `with Plex an alias lookup TMDB has no record for leaves the IMDb ID to its own lookup`() = capped {
        val repository = repository(NamingStrategy.Plex, handler = plexHandler(show = null, movie = null))
        val show = with(LoggingContext()) {
            repository.withAlternativeTitles(listOf(demonSlayer), "kimetsu no yaiba").getOrThrow()
            repository.withImdbId(demonSlayerMatch).getOrThrow()
        }
        assertEquals("tt85937", show.imdbId)
        assertEquals(listOf("/3/tv/85937", "/3/tv/85937/external_ids"), requests)
    }

    @Test
    fun `with Plex a failed alias lookup leaves the IMDb ID to its own lookup`() = capped {
        var failing = true
        val answers = plexHandler(showDetails("tt9335498"), movie = null)
        val repository = repository(NamingStrategy.Plex) { request ->
            if (request.url.encodedPath == "/3/tv/85937" && failing) respondJson("""{"success":false}""", HttpStatusCode.InternalServerError)
            else answers(request)
        }
        val show = with(LoggingContext()) {
            // Nothing else resembles the query, so the missing alias may be the match: the search fails.
            assertTrue(repository.withAlternativeTitles(listOf(demonSlayer), "kimetsu no yaiba").isFailure)
            failing = false
            repository.withImdbId(demonSlayerMatch).getOrThrow()
        }
        // The failed lookup kept neither the aliases nor an IMDb ID: the ID is looked up on its own, not taken as none.
        assertEquals("tt85937", show.imdbId)
        assertEquals(listOf("/3/tv/85937", "/3/tv/85937/external_ids"), requests)
    }

    @Test
    fun `with Plex an alias lookup with no answer leaves the IMDb ID to a lookup that must succeed`() = capped {
        var externalIdsFail = true
        val answers = plexHandler(showDetails("tt9335498"), movie = null)
        val timeouts = TmdbTimeouts(requestMillis = 5_000, optionalRequestMillis = 200, retries = 0, retryDelay = {})
        val repository = repository(NamingStrategy.Plex, timeouts) { request ->
            when {
                request.url.encodedPath == "/3/tv/85937" -> awaitCancellation()
                request.url.encodedPath.endsWith("/external_ids") && externalIdsFail -> respondJson("""{"success":false}""", HttpStatusCode.ServiceUnavailable)
                else -> answers(request)
            }
        }
        with(LoggingContext()) {
            assertTrue(repository.withAlternativeTitles(listOf(demonSlayer), "kimetsu no yaiba").isFailure)
            // The IMDb ID is still required: its own lookup failing fails, rather than naming the show without one.
            assertEquals("HTTP 503 fetching external IDs of 'Demon Slayer: Kimetsu no Yaiba'", repository.withImdbId(demonSlayerMatch).exceptionOrNull()?.message)
            externalIdsFail = false
            assertEquals("tt85937", repository.withImdbId(demonSlayerMatch).getOrThrow().imdbId)
        }
        assertEquals(listOf("/3/tv/85937", "/3/tv/85937/external_ids", "/3/tv/85937/external_ids"), requests)
    }

    @Test
    fun `with Jellyfin the alias lookup stays the alternative titles alone`() = capped {
        val repository = repository(handler = aliasHandler())
        with(LoggingContext()) { repository.withAlternativeTitles(listOf(demonSlayer), "kimetsu no yaiba").getOrThrow() }
        assertEquals(listOf("/3/tv/85937/alternative_titles"), requests)
    }

    @Test
    fun `with Plex a record looked up by ID brings its IMDb ID along`() = capped {
        val repository = repository(NamingStrategy.Plex) { request ->
            when (request.url.encodedPath) {
                "/3/tv/85937" -> respondJson(showDetails("tt9335498"))
                else -> respondJson(TMDB_NOT_FOUND, HttpStatusCode.NotFound)
            }
        }
        val show = with(LoggingContext()) {
            repository.findShow(MediaId(MediaId.Database.TMDB, "85937")).getOrThrow()
            // A search result for the same record, which carries no IMDb ID, takes it from the record.
            repository.withImdbId(demonSlayerMatch).getOrThrow()
        }
        assertEquals("tt9335498", show.imdbId)
        assertEquals(listOf("/3/tv/85937"), requests)
    }

    @Test
    fun `with Plex a record looked up by ID without an IMDb ID leaves it to its own lookup`() = capped {
        val repository = repository(NamingStrategy.Plex) { request ->
            when (request.url.encodedPath) {
                // No external_ids object: the answer says nothing about the IMDb ID.
                "/3/tv/85937" -> respondJson("""{"id": 85937, "name": "Demon Slayer: Kimetsu no Yaiba", "first_air_date": "2019-04-06", "alternative_titles": $aliases}""")
                "/3/tv/85937/external_ids" -> respondJson("""{"id": 85937, "imdb_id": "tt9335498"}""")
                "/3/movie/1721501" -> respondJson("""{"id": 1721501, "title": "?", "release_date": "2026-01-01", "imdb_id": null, "alternative_titles": {"titles": []}}""")
                "/3/movie/1721501/external_ids" -> respondJson("""{"id": 1721501, "imdb_id": null}""")
                else -> respondJson(TMDB_NOT_FOUND, HttpStatusCode.NotFound)
            }
        }
        val (show, movie) = with(LoggingContext()) {
            assertNull(repository.findShow(MediaId(MediaId.Database.TMDB, "85937")).getOrThrow()?.imdbId)
            assertNull(repository.findMovie(MediaId(MediaId.Database.TMDB, "1721501", fromTitle = true)).getOrThrow()?.imdbId)
            repository.withImdbId(demonSlayerMatch).getOrThrow() to repository.withImdbId(CanonicalMedia.Movie("?", 2026, tmdbId = "1721501")).getOrThrow()
        }
        // The IMDb ID is looked up on its own, as for a search result: the show's is found, and the movie has none.
        assertEquals("tt9335498", show.imdbId)
        assertNull(movie.imdbId)
        assertEquals(listOf("/3/tv/85937", "/3/movie/1721501", "/3/tv/85937/external_ids", "/3/movie/1721501/external_ids"), requests)
    }
}
