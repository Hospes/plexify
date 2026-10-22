package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.OperationMode
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceFailureTest {

    @Test
    fun `a missing source counts as failed`() = runTest {
        for (isTestMode in listOf(true, false)) {
            val missing = Path(SystemTemporaryDirectory, "plexify-missing-${Random.nextLong().toULong()}", "Movie (2020).mkv")
            val stats = processSource(missing, isTestMode = isTestMode)
            assertEquals(1, stats.failed, "test mode: $isTestMode")
            assertEquals(0, stats.skipped, "test mode: $isTestMode")
        }
    }

    @Test
    fun `a source that can't be examined counts as failed`() = runTest {
        // No file system accepts a name this long: Linux fails with ENAMETOOLONG, Windows with an invalid-name error.
        val stats = processSource(Path(SystemTemporaryDirectory, "x".repeat(300)))
        assertEquals(1, stats.failed)
        assertEquals(0, stats.skipped)
    }

    @Test
    fun `each missing source counts once`() = runTest {
        val dir = Path(SystemTemporaryDirectory, "plexify-missing-${Random.nextLong().toULong()}")
        val stats = processSource(Path(dir, "a.mkv"), Path(dir, "b"))
        assertEquals(2, stats.failed)
    }
}
