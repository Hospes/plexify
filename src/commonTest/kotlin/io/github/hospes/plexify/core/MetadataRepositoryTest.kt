package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataRepositoryTest {

    @Test
    fun `a movie and a show with one TMDB number look their IMDb IDs up apart`() = runTest {
        val provider = FakeTmdb(emptyList())
        val repository = MetadataRepository(MetadataService(listOf(provider), NamingStrategy.Plex))
        with(LoggingContext()) {
            repository.withImdbId(CanonicalMedia.Movie(title = "Some Film", year = 2008, tmdbId = "1396")).getOrThrow()
            repository.withImdbId(CanonicalMedia.TvShow(title = "Breaking Bad", year = 2008, tmdbId = "1396")).getOrThrow()
            repository.withImdbId(CanonicalMedia.TvShow(title = "Breaking Bad", year = 2008, tmdbId = "1396")).getOrThrow()
        }
        assertEquals(listOf("1396", "1396"), provider.externalIdLookups)
    }
}
