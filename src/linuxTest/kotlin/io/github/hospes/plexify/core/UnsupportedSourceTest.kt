package io.github.hospes.plexify.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import platform.posix.chmod
import platform.posix.geteuid
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class UnsupportedSourceTest {

    @Test
    fun `a source that is neither a file nor a directory counts as failed`() = runTest {
        val stats = processSource(Path("/dev/null"))
        assertEquals(1, stats.failed)
        assertEquals(0, stats.skipped)
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun `a source that can't be examined counts as failed`() = runTest {
        if (geteuid() == 0u) return@runTest // root can read anything
        val dir = Path(SystemTemporaryDirectory, "plexify-locked-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(dir)
        try {
            check(chmod(dir.toString(), 0u) == 0)
            val stats = processSource(Path(dir, "Movie (2020).mkv"))
            assertEquals(1, stats.failed)
        } finally {
            chmod(dir.toString(), "700".toUInt(8))
            SystemFileSystem.delete(dir)
        }
    }
}
