package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaId
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Live (October 2026), the curl engine answers the first request after a timed-out one, its own retry included, with
 * a 200 that has no headers. Ktor can't read its body, and that must fail the lookup instead of passing for "TMDB has
 * nothing": no IMDb ID (a Plex folder without its tag), no episode groups, no record for the ID in the path.
 */
class TmdbUnreadableResponseTest {

    // An access token, as release binaries use: it travels in a header, so the error isn't redacted and keeps its type.
    private val token = TmdbCredentials(apiKey = null, accessToken = "secret-token", source = TmdbCredentials.Source.USER)
    private val breakingBad = CanonicalMedia.TvShow(title = "Breaking Bad", year = 2008, tmdbId = "1396")

    // No Content-Type, so no converter takes the body.
    private fun provider(credentials: TmdbCredentials = token) =
        TmdbProvider(credentials) { MockEngine { respond("""{"imdb_id":"tt0903747","results":[]}""", HttpStatusCode.OK) } }

    private val service = MetadataService(listOf(provider()), NamingStrategy.Plex)

    private fun assertUnreadable(action: String, result: Result<*>) {
        val error = result.exceptionOrNull()
        assertIs<IllegalStateException>(error, "$action: $result")
        assertEquals("TMDB sent a response that could not be read $action", error.message)
    }

    @Test
    fun `an unreadable IMDb lookup fails instead of finding no IMDb ID`(): Unit = runBlocking {
        assertUnreadable("fetching external IDs of 'Breaking Bad'", provider().externalIds(breakingBad))
        assertUnreadable("fetching external IDs of 'Breaking Bad'", with(LoggingContext()) { service.getExternalIds(breakingBad) })
    }

    @Test
    fun `other unreadable lookups fail instead of finding nothing`(): Unit = runBlocking {
        with(LoggingContext()) {
            assertUnreadable("fetching episode groups of 'Breaking Bad'", service.getEpisodeGroups(breakingBad))
            assertUnreadable("looking up movie tmdb-603", service.findMovie(MediaId(MediaId.Database.TMDB, "603")))
            assertUnreadable("looking up show tmdb-1396", service.findShow(MediaId(MediaId.Database.TMDB, "1396")))
            assertUnreadable("looking up imdb-tt0903747", service.findShow(MediaId(MediaId.Database.IMDB, "tt0903747")))
            assertUnreadable("fetching season 1 of 'Breaking Bad'", service.getSeason(breakingBad, 1))
            assertUnreadable("searching for 'Breaking Bad'", service.search("Breaking Bad", null))
        }
    }

    @Test
    fun `an unreadable response doesn't print an api key`(): Unit = runBlocking {
        val apiKey = TmdbCredentials(apiKey = "secret-key", accessToken = null, source = TmdbCredentials.Source.USER)

        // Ktor's cause names the URL, api_key included, so the error comes back redacted.
        val error = provider(apiKey).externalIds(breakingBad).exceptionOrNull()

        assertEquals("TMDB sent a response that could not be read fetching external IDs of 'Breaking Bad'", error?.message)
        val printed = error!!.stackTraceToString()
        assertFalse("secret-key" in printed, printed)
    }
}
