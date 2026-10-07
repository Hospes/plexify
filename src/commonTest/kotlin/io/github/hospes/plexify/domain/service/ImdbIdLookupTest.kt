package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.data.MetadataCache
import io.github.hospes.plexify.data.MetadataNotFoundException
import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImdbIdLookupTest {

    private class FakeProvider(
        private val imdbId: Result<String?>,
        override val id: String = "tmdb",
        override val supportedIds: Set<String> = setOf("tmdbid", "imdbid"),
    ) : MetadataProvider {
        var lookups = 0

        override suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>> =
            Result.success(emptyList())

        override suspend fun imdbId(media: CanonicalMedia): Result<String?> {
            lookups++
            return imdbId
        }
    }

    private val matrix = CanonicalMedia.Movie(title = "The Matrix", year = 1999, tmdbId = "603")

    @Test
    fun `looks up the imdb id when the template uses it`() = runTest {
        val provider = FakeProvider(Result.success("tt0133093"))
        val service = MetadataService(listOf(provider), NamingStrategy.Plex)

        assertEquals("tt0133093", with(LoggingContext()) { service.getImdbId(matrix) })
        assertEquals(1, provider.lookups)
    }

    @Test
    fun `skips the lookup when the template has no imdbid placeholder`() = runTest {
        val provider = FakeProvider(Result.success("tt0133093"))
        val service = MetadataService(listOf(provider), NamingStrategy.Jellyfin)

        assertNull(with(LoggingContext()) { service.getImdbId(matrix) })
        assertEquals(0, provider.lookups)
    }

    @Test
    fun `a record without an imdb id leaves it empty`() = runTest {
        val provider = FakeProvider(Result.failure(MetadataNotFoundException("HTTP 404")))
        val service = MetadataService(listOf(provider), NamingStrategy.Plex)

        assertNull(with(LoggingContext()) { service.getImdbId(matrix) })
    }

    @Test
    fun `falls through providers that cannot supply the id`() = runTest {
        val unsupported = FakeProvider(Result.failure(UnsupportedOperationException()), id = "tmdb")
        val other = FakeProvider(Result.success("tt0133093"), id = "other", supportedIds = setOf("imdbid"))
        val service = MetadataService(listOf(unsupported, other), NamingStrategy.Plex)

        assertEquals("tt0133093", with(LoggingContext()) { service.getImdbId(matrix) })
    }

    @Test
    fun `cache looks up each key once including misses`() = runTest {
        val cache = MetadataCache()
        var lookups = 0

        repeat(3) { assertEquals("tt0133093", cache.getOrPutImdbId("movie:603") { lookups++; "tt0133093" }) }
        repeat(3) { assertNull(cache.getOrPutImdbId("tv:1399") { lookups++; null }) }

        assertEquals(2, lookups)
    }
}
