package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.data.MetadataCache
import io.github.hospes.plexify.data.MetadataNotFoundException
import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalIdsLookupTest {

    private class FakeProvider(
        private val ids: Result<ExternalIds>,
        override val id: String = "tmdb",
        override val supportedIds: Set<String> = setOf("tmdbid", "imdbid", "tvdbid"),
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
    private val breakingBadIds = ExternalIds(imdbId = "tt0903747", tvdbId = "81189")

    @Test
    fun `looks up the imdb id when the template uses it`() = runTest {
        val provider = FakeProvider(Result.success(ExternalIds(imdbId = "tt0133093")))
        val service = MetadataService(listOf(provider), NamingStrategy.Plex)

        assertEquals("tt0133093", with(LoggingContext()) { service.getExternalIds(matrix) }?.imdbId)
        assertEquals(1, provider.lookups)
    }

    private val tvdbTemplate = NamingStrategy.Custom("{CleanTitle} ({year}) [tvdbid-{tvdbid}]/{CleanTitle}.{ext}")

    @Test
    fun `looks up show ids when the template uses tvdbid`() = runTest {
        val provider = FakeProvider(Result.success(breakingBadIds))
        val service = MetadataService(listOf(provider), tvdbTemplate)

        assertEquals("81189", with(LoggingContext()) { service.getExternalIds(breakingBad) }?.tvdbId)
        assertEquals(1, provider.lookups)
    }

    @Test
    fun `skips movies when the template only uses tvdbid`() = runTest {
        val provider = FakeProvider(Result.success(ExternalIds(imdbId = "tt0133093")))
        val service = MetadataService(listOf(provider), tvdbTemplate)

        assertNull(with(LoggingContext()) { service.getExternalIds(matrix) })
        assertEquals(0, provider.lookups)
    }

    @Test
    fun `the jellyfin template needs no external ids`() = runTest {
        val provider = FakeProvider(Result.success(breakingBadIds))
        val service = MetadataService(listOf(provider), NamingStrategy.Jellyfin)

        assertNull(with(LoggingContext()) { service.getExternalIds(breakingBad) })
        assertNull(with(LoggingContext()) { service.getExternalIds(matrix) })
        assertEquals(0, provider.lookups)
    }

    @Test
    fun `skips the lookup when the template uses no external id`() = runTest {
        val provider = FakeProvider(Result.success(breakingBadIds))
        val service = MetadataService(listOf(provider), NamingStrategy.Custom("{CleanTitle} [tmdbid-{tmdbid}]/{CleanTitle}.{ext}"))

        assertNull(with(LoggingContext()) { service.getExternalIds(breakingBad) })
        assertEquals(0, provider.lookups)
    }

    @Test
    fun `a record without external ids leaves them empty`() = runTest {
        val provider = FakeProvider(Result.failure(MetadataNotFoundException("HTTP 404")))
        val service = MetadataService(listOf(provider), NamingStrategy.Plex)

        assertNull(with(LoggingContext()) { service.getExternalIds(matrix) })
    }

    @Test
    fun `falls through providers that cannot supply the ids`() = runTest {
        val unsupported = FakeProvider(Result.failure(UnsupportedOperationException()), id = "tmdb")
        val other = FakeProvider(Result.success(ExternalIds(imdbId = "tt0133093")), id = "other", supportedIds = setOf("imdbid"))
        val service = MetadataService(listOf(unsupported, other), NamingStrategy.Plex)

        assertEquals("tt0133093", with(LoggingContext()) { service.getExternalIds(matrix) }?.imdbId)
    }

    @Test
    fun `cache looks up each key once including misses`() = runTest {
        val cache = MetadataCache()
        var lookups = 0

        repeat(3) { assertEquals(breakingBadIds, cache.getOrPutExternalIds("tv:1396") { lookups++; breakingBadIds }) }
        repeat(3) { assertNull(cache.getOrPutExternalIds("movie:603") { lookups++; null }) }

        assertEquals(2, lookups)
    }
}
