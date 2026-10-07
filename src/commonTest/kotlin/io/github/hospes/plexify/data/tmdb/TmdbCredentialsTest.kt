package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.tmdb.TmdbCredentials.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TmdbCredentialsTest {

    @Test
    fun `user token wins over the built-in key`() {
        val credentials = TmdbCredentials.resolve(null, "user-token", "built-in-key", "built-in-token")

        assertNotNull(credentials)
        assertEquals(Source.USER, credentials.source)
        assertEquals("user-token", credentials.accessToken)
        assertNull(credentials.apiKey)
    }

    @Test
    fun `user api key alone is not mixed with the built-in token`() {
        val credentials = TmdbCredentials.resolve("user-key", null, "", "built-in-token")

        assertNotNull(credentials)
        assertEquals(Source.USER, credentials.source)
        assertEquals("user-key", credentials.apiKey)
        assertNull(credentials.accessToken)
    }

    @Test
    fun `falls back to the built-in credentials when the user gives none`() {
        val credentials = TmdbCredentials.resolve(null, null, "built-in-key", "built-in-token")

        assertNotNull(credentials)
        assertEquals(Source.BUILT_IN, credentials.source)
        assertEquals("built-in-key", credentials.apiKey)
        assertEquals("built-in-token", credentials.accessToken)
    }

    @Test
    fun `blank user values count as not given`() {
        val credentials = TmdbCredentials.resolve("  ", "", "", "built-in-token")

        assertNotNull(credentials)
        assertEquals(Source.BUILT_IN, credentials.source)
    }

    @Test
    fun `user values are trimmed`() {
        val credentials = TmdbCredentials.resolve(null, " user-token\n", "", "")

        assertEquals("user-token", credentials?.accessToken)
    }

    @Test
    fun `no credentials anywhere resolves to null`() {
        assertNull(TmdbCredentials.resolve(null, null, "", ""))
    }

    @Test
    fun `toString never prints the secrets`() {
        val text = TmdbCredentials.resolve("user-key", "user-token", "", "").toString()

        assertFalse("user-key" in text)
        assertFalse("user-token" in text)
    }
}
