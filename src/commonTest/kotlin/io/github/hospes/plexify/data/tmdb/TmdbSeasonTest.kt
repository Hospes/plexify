package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.MetadataNotFoundException
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** `tv/{id}/season/{n}`, with a body hand-written in the shape of TMDB's (October 2026). */
class TmdbSeasonTest {

    private val breakingBad = CanonicalMedia.TvShow(title = "Breaking Bad", year = 2008, tmdbId = "1396")

    private val seasonOne = """{"_id":"52542282760ee313280017f9","air_date":"2008-01-20","episodes":[""" +
        """{"air_date":"2008-01-20","episode_number":1,"episode_type":"standard","id":62085,"name":"Pilot","runtime":59,"season_number":1,"show_id":1396},""" +
        """{"air_date":"2008-01-27","episode_number":2,"episode_type":"standard","id":62086,"name":"Cat's in the Bag...","runtime":49,"season_number":1,"show_id":1396}""" +
        """],"name":"Season 1","overview":"","id":3572,"poster_path":null,"season_number":1,"vote_average":8.3}"""

    private fun service(provider: TmdbProvider) = MetadataService(listOf(provider), NamingStrategy.Jellyfin)

    @Test
    fun `a season is read with TMDB's numbering and titles`() = runBlocking {
        val tmdb = TmdbResponder(mapOf("/3/tv/1396/season/1" to seasonOne))

        val season = mockProvider(credentials = tokenCredentials, handler = tmdb.handler).season(breakingBad, 1).getOrThrow()

        assertEquals(1, season.seasonNumber)
        assertEquals(listOf(Triple(1, 1, "Pilot"), Triple(1, 2, "Cat's in the Bag...")), season.episodes.map { Triple(it.season, it.episode, it.title) })
        assertEquals(listOf("/3/tv/1396/season/1"), tmdb.paths)
    }

    @Test
    fun `a season TMDB doesn't have comes back empty`() = runBlocking {
        val provider = mockProvider(credentials = tokenCredentials, handler = TmdbResponder(notFound = setOf("/3/tv/1396/season/9")).handler)

        assertIs<MetadataNotFoundException>(provider.season(breakingBad, 9).exceptionOrNull())
        val season = with(LoggingContext()) { service(provider).getSeason(breakingBad, 9) }.getOrThrow()

        assertEquals(CanonicalMedia.Season(breakingBad, 9, emptyList()), season)
    }

    @Test
    fun `a season that failed to load is an error not an empty season`() = runBlocking {
        val provider = mockProvider(credentials = tokenCredentials) { respondJson("""{"status_code":11,"status_message":"Internal error."}""", HttpStatusCode.InternalServerError) }

        val error = with(LoggingContext()) { service(provider).getSeason(breakingBad, 1) }.exceptionOrNull()

        assertEquals("HTTP 500 fetching season 1 of 'Breaking Bad'", error?.message)
    }
}
