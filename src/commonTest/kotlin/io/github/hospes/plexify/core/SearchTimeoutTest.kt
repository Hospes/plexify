package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.MetadataCache
import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.data.MetadataTimeoutException
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTimeoutTest {

    private class FakeTmdb(private val search: () -> Result<List<MediaSearchResult>>) : MetadataProvider {
        override val id = "tmdb"
        override val supportedIds = setOf("tmdbid")
        var searches = 0

        override suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>> {
            searches++
            return search()
        }
    }

    private object NoOpOrganizer : FileOrganizer {
        override fun organize(
            sourceFile: Path,
            destinationRoot: Path,
            media: CanonicalMedia,
            parsedInfo: ParsedMediaInfo,
            mode: OperationMode,
            isTestMode: Boolean,
        ): Result<OrganizeOutcome> = Result.success(OrganizeOutcome.Organized(destinationRoot))
    }

    private val timedOut = { Result.failure<List<MediaSearchResult>>(MetadataTimeoutException("TMDB request timed out searching")) }

    /** Processes empty files with these names, from a fresh temporary directory, with a fresh processor. */
    private suspend fun run(provider: FakeTmdb, vararg fileNames: String): MediaProcessor.Stats {
        val processor = MediaProcessor(
            metadataService = MetadataService(listOf(provider), NamingStrategy.Jellyfin),
            fileOrganizer = NoOpOrganizer,
            cache = MetadataCache(),
        )
        val dir = Path(SystemTemporaryDirectory, "plexify-timeout-test-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(dir)
        val files = fileNames.map { Path(dir, it) }
        try {
            files.forEach { SystemFileSystem.sink(it).buffered().close() }
            with(LoggingContext()) {
                processor.process(dir, Path(dir, "library"), OperationMode.HARDLINK, isTestMode = true)
            }
        } finally {
            files.forEach { SystemFileSystem.delete(it, mustExist = false) }
            SystemFileSystem.delete(dir, mustExist = false)
        }
        return processor.stats
    }

    @Test
    fun `a movie whose search times out counts as failed`() = runTest {
        val stats = run(FakeTmdb(timedOut), "Dune.Part.Two.2024.1080p.WEB-DL.mkv")

        assertEquals(1, stats.failed)
        assertEquals(0, stats.skipped)
    }

    @Test
    fun `episodes whose show search times out count as failed and search again`() = runTest {
        val provider = FakeTmdb(timedOut)

        val stats = run(provider, "Ghosts.S04E01.1080p.WEB.mkv", "Ghosts.S04E02.1080p.WEB.mkv")

        assertEquals(2, stats.failed)
        assertEquals(0, stats.skipped)
        // A timeout says nothing about the show, so it is not cached as a miss.
        assertEquals(2, provider.searches)
    }

    @Test
    fun `a search with no results is still skipped`() = runTest {
        val stats = run(FakeTmdb { Result.success(emptyList()) }, "Dune.Part.Two.2024.1080p.WEB-DL.mkv")

        assertEquals(0, stats.failed)
        assertEquals(1, stats.skipped)
    }
}
