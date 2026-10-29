package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.ExternalIds
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MetadataCacheTest {

    private val matrix = TmdbRecord(TmdbRecord.Type.MOVIE, "603")
    private val breakingBad = TmdbRecord(TmdbRecord.Type.TV, "1396")
    private val breakingBadIds = ExternalIds(imdbId = "tt0903747")

    @Test
    fun `cache looks up each key once including misses`() = runTest {
        val cache = MetadataCache()
        var lookups = 0

        repeat(3) { assertEquals(breakingBadIds, cache.getOrPutExternalIds(breakingBad) { lookups++; Result.success(breakingBadIds) }.getOrThrow()) }
        repeat(3) { assertNull(cache.getOrPutExternalIds(matrix) { lookups++; Result.success(null) }.getOrThrow()) }

        assertEquals(2, lookups)
    }

    @Test
    fun `cache looks a failed lookup up again`() = runTest {
        val cache = MetadataCache()
        var lookups = 0

        repeat(2) {
            val result = cache.getOrPutExternalIds(breakingBad) { lookups++; Result.failure(IllegalStateException("HTTP 503")) }
            assertIs<IllegalStateException>(result.exceptionOrNull())
        }
        assertEquals(breakingBadIds, cache.getOrPutExternalIds(breakingBad) { lookups++; Result.success(breakingBadIds) }.getOrThrow())
        assertEquals(breakingBadIds, cache.getOrPutExternalIds(breakingBad) { lookups++; Result.success(null) }.getOrThrow())

        assertEquals(3, lookups)
    }
}
