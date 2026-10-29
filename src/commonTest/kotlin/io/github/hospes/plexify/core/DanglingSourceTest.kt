package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DanglingSourceTest {

    private val root = Path(SystemTemporaryDirectory, "plexify-dangling-source-test-${Random.nextLong().toULong()}")
        .also { PlatformFileSystem.createDirectories(it) }

    @AfterTest
    fun cleanUp() {
        PlatformFileSystem.list(root).forEach { PlatformFileSystem.delete(it) }
        PlatformFileSystem.delete(root)
    }

    @Test
    fun `a source that is a broken symlink counts as failed`() = runTest {
        val source = Path(root, "Inception.2010.1080p.mkv")
        if (!createSymbolicLink(source, "missing.mkv")) return@runTest
        val provider = FakeTmdb(results = emptyList())
        val organizer = RecordingOrganizer()
        val processor = MediaProcessor(MetadataRepository(MetadataService(listOf(provider), NamingStrategy.Jellyfin)), organizer)

        with(LoggingContext()) {
            processor.process(source, Path(root, "library"), OperationMode.HARDLINK, isTestMode = true)
        }

        assertEquals(1, processor.stats.failed)
        assertEquals(0, processor.stats.skipped)
        assertTrue(provider.searchedTitles.isEmpty())
        assertTrue(organizer.organized.isEmpty())
    }
}
