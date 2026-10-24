package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.data.MetadataCache
import io.github.hospes.plexify.data.MetadataNotFoundException
import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.data.MetadataTimeoutException
import io.github.hospes.plexify.data.MetadataUnreachableException
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExternalIdsLookupTest {

    private class FakeProvider(
        private val ids: Result<ExternalIds>,
        override val id: String = "tmdb",
        override val supportedIds: Set<String> = setOf("tmdbid", "imdbid"),
    ) : MetadataProvider {
        var lookups = 0

        override suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>> =
            Result.success(emptyList())

        override suspend fun externalIds(media: CanonicalMedia): Result<ExternalIds> {
            lookups++
            return ids
        }
    }

    private val matrix = CanonicalMedia.Movie(title = "The Matrix", year = 1999, tmdbId = "603")
    private val breakingBad = CanonicalMedia.TvShow(title = "Breaking Bad", year = 2008, tmdbId = "1396")
    private val breakingBadIds = ExternalIds(imdbId = "tt0903747")

    private suspend fun MetadataService.lookUp(media: CanonicalMedia) = with(LoggingContext()) { getExternalIds(media) }

    @Test
    fun `looks up the imdb id when the template uses it`() = runTest {
        val provider = FakeProvider(Result.success(ExternalIds(imdbId = "tt0133093")))
        val service = MetadataService(listOf(provider), NamingStrategy.Plex)

        assertEquals("tt0133093", service.lookUp(matrix).getOrThrow()?.imdbId)
        assertEquals(1, provider.lookups)
    }

    @Test
    fun `looks up the show imdb id for plex`() = runTest {
        val provider = FakeProvider(Result.success(breakingBadIds))
        val service = MetadataService(listOf(provider), NamingStrategy.Plex)

        assertEquals("tt0903747", service.lookUp(breakingBad).getOrThrow()?.imdbId)
        assertEquals(1, provider.lookups)
    }

    @Test
    fun `the jellyfin template needs no external ids`() = runTest {
        val provider = FakeProvider(Result.success(breakingBadIds))
        val service = MetadataService(listOf(provider), NamingStrategy.Jellyfin)

        assertNull(service.lookUp(breakingBad).getOrThrow())
        assertNull(service.lookUp(matrix).getOrThrow())
        assertEquals(0, provider.lookups)
    }

    @Test
    fun `a record without external ids leaves them empty`() = runTest {
        val provider = FakeProvider(Result.failure(MetadataNotFoundException("HTTP 404")))
        val service = MetadataService(listOf(provider), NamingStrategy.Plex)

        assertNull(service.lookUp(matrix).getOrThrow())
    }

    @Test
    fun `a record without an imdb id leaves it empty`() = runTest {
        val provider = FakeProvider(Result.success(ExternalIds(imdbId = null)))
        val service = MetadataService(listOf(provider), NamingStrategy.Plex)

        assertNull(service.lookUp(matrix).getOrThrow())
    }

    @Test
    fun `a lookup that failed fails`() = runTest {
        val failures = listOf(
            IllegalStateException("HTTP 503 fetching external IDs of 'The Matrix'"),
            MetadataTimeoutException("TMDB request timed out fetching external IDs of 'The Matrix'"),
            MetadataUnreachableException("Not fetching external IDs of 'The Matrix': TMDB stopped answering earlier in this run"),
        )
        for (failure in failures) {
            val service = MetadataService(listOf(FakeProvider(Result.failure(failure))), NamingStrategy.Plex)

            assertEquals(failure, service.lookUp(matrix).exceptionOrNull())
        }
    }

    @Test
    fun `another provider's imdb id wins over a failed lookup`() = runTest {
        val failing = FakeProvider(Result.failure(IllegalStateException("HTTP 503")), id = "tmdb")
        val other = FakeProvider(Result.success(ExternalIds(imdbId = "tt0133093")), id = "other", supportedIds = setOf("imdbid"))
        val service = MetadataService(listOf(failing, other), NamingStrategy.Plex)

        assertEquals("tt0133093", service.lookUp(matrix).getOrThrow()?.imdbId)
    }

    @Test
    fun `falls through providers that cannot supply the ids`() = runTest {
        val unsupported = FakeProvider(Result.failure(UnsupportedOperationException()), id = "tmdb")
        val other = FakeProvider(Result.success(ExternalIds(imdbId = "tt0133093")), id = "other", supportedIds = setOf("imdbid"))
        val service = MetadataService(listOf(unsupported, other), NamingStrategy.Plex)

        assertEquals("tt0133093", service.lookUp(matrix).getOrThrow()?.imdbId)
    }

    @Test
    fun `cache looks up each key once including misses`() = runTest {
        val cache = MetadataCache()
        var lookups = 0

        repeat(3) { assertEquals(breakingBadIds, cache.getOrPutExternalIds("tv:1396") { lookups++; Result.success(breakingBadIds) }.getOrThrow()) }
        repeat(3) { assertNull(cache.getOrPutExternalIds("movie:603") { lookups++; Result.success(null) }.getOrThrow()) }

        assertEquals(2, lookups)
    }

    @Test
    fun `cache looks a failed lookup up again`() = runTest {
        val cache = MetadataCache()
        var lookups = 0

        repeat(2) {
            val result = cache.getOrPutExternalIds("tv:1396") { lookups++; Result.failure(IllegalStateException("HTTP 503")) }
            assertIs<IllegalStateException>(result.exceptionOrNull())
        }
        assertEquals(breakingBadIds, cache.getOrPutExternalIds("tv:1396") { lookups++; Result.success(breakingBadIds) }.getOrThrow())
        assertEquals(breakingBadIds, cache.getOrPutExternalIds("tv:1396") { lookups++; Result.success(null) }.getOrThrow())

        assertEquals(3, lookups)
    }
}
