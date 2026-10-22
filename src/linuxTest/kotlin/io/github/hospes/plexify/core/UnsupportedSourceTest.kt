package io.github.hospes.plexify.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import platform.posix.chmod
import platform.posix.geteuid
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnsupportedSourceTest {

    @Test
    fun `a source that is neither a file nor a directory counts as failed`() = runTest {
        val stats = processSource(Path("/dev/null"))
        assertEquals(1, stats.failed)
        assertEquals(0, stats.skipped)
    }

    @Test
    fun `a source that can't be examined counts as failed`() = withLockedDirectory { dir ->
        val source = Path(dir, "Movie (2020).mkv")
        assertFailsWith<IOException> { PlatformFileSystem.kind(source) } // not "doesn't exist"
        val stats = processSource(source)
        assertEquals(1, stats.failed)
        assertEquals(0, stats.skipped)
    }

    @Test
    fun `a source directory that can't be listed counts as failed`() = withLockedDirectory { dir ->
        val stats = processSource(dir)
        assertEquals(1, stats.failed)
        assertEquals(0, stats.skipped)
    }

    @Test
    fun `a directory that can't be listed inside the source counts as failed`() = withLockedDirectory { dir ->
        val stats = processSource(dir.parent!!)
        assertEquals(1, stats.failed)
        assertEquals(0, stats.skipped)
    }

    /**
     * Runs [block] with a directory holding a movie that no one but root can list or enter, inside a directory of
     * its own. Does nothing as root, who can read anything.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun withLockedDirectory(block: suspend (Path) -> Unit) = runTest {
        if (geteuid() == 0u) return@runTest
        val parent = Path(SystemTemporaryDirectory, "plexify-locked-${Random.nextLong().toULong()}")
        val dir = Path(parent, "Movie (2020)")
        val movie = Path(dir, "Movie (2020).mkv")
        SystemFileSystem.createDirectories(dir)
        SystemFileSystem.sink(movie).buffered().close()
        try {
            check(chmod(dir.toString(), 0u) == 0)
            block(dir)
        } finally {
            chmod(dir.toString(), "700".toUInt(8))
            SystemFileSystem.delete(movie)
            SystemFileSystem.delete(dir)
            SystemFileSystem.delete(parent)
        }
    }
}
