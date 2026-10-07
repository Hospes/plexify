package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.MetadataNotFoundException
import io.github.hospes.plexify.data.tmdb.TmdbCredentials.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TmdbRedactionTest {

    private val keyOnly = TmdbCredentials(apiKey = "0123456789abcdef", accessToken = null, source = Source.BUILT_IN)
    private val tokenOnly = TmdbCredentials(apiKey = null, accessToken = "eyJhbGciOi.payload.signature", source = Source.USER)

    // What Ktor's curl engine reports when a request can't connect.
    private val curlMessage = "Connection failed for request: CurlRequestData(url='https://api.themoviedb.org/3/" +
            "search/multi?query=Dune&include_adult=true&page=1&api_key=0123456789abcdef', method='GET', content=0 bytes)"

    @Test
    fun `api key in a curl connection error is masked`() {
        val text = keyOnly.redact(curlMessage)

        assertFalse("0123456789abcdef" in text)
        assertTrue("query=Dune&include_adult=true&page=1&api_key=***'" in text)
    }

    @Test
    fun `api_key query value is masked even when it is not this run's key`() {
        val text = tokenOnly.redact("GET https://api.themoviedb.org/3/movie/1?api_key=someone-elses-key&language=en")

        assertEquals("GET https://api.themoviedb.org/3/movie/1?api_key=***&language=en", text)
    }

    @Test
    fun `bearer token is masked`() {
        val text = tokenOnly.redact("Authorization: Bearer eyJhbGciOi.payload.signature")

        assertEquals("Authorization: Bearer ***", text)
    }

    @Test
    fun `the token is masked wherever it appears`() {
        val text = tokenOnly.redact("headers=[eyJhbGciOi.payload.signature]")

        assertEquals("headers=[***]", text)
    }

    @Test
    fun `text without credentials is unchanged`() {
        assertEquals("HTTP 500 searching for 'Dune'", keyOnly.redact("HTTP 500 searching for 'Dune'"))
    }

    @Test
    fun `an error without credentials keeps its identity and type`() {
        val error = MetadataNotFoundException("HTTP 404 fetching season 3 of 'Dune'")

        assertSame(error, keyOnly.redact(error))
    }

    @Test
    fun `an error carrying the key is retold with the key masked`() {
        val redacted = keyOnly.redact(IllegalStateException(curlMessage))

        assertIs<TmdbRedactedException>(redacted)
        assertFalse("0123456789abcdef" in redacted.message.orEmpty())
        assertTrue(redacted.toString().startsWith("IllegalStateException: Connection failed"))
    }

    @Test
    fun `a key in a cause is masked through the whole chain`() {
        val error = RuntimeException("search failed", IllegalStateException(curlMessage))

        val redacted = keyOnly.redact(error)

        assertIs<TmdbRedactedException>(redacted)
        assertEquals("search failed", redacted.message)
        val cause = assertNotNull(redacted.cause)
        assertFalse("0123456789abcdef" in cause.toString())
        assertFalse("0123456789abcdef" in redacted.stackTraceToString())
    }
}
