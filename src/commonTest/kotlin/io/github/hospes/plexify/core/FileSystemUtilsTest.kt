package io.github.hospes.plexify.core

import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSystemUtilsTest {

    private val root = Path(SystemTemporaryDirectory, "plexify-fs-test-${Random.nextLong().toULong()}")
        .also { PlatformFileSystem.createDirectories(it) }

    @AfterTest
    fun cleanUp() = deleteRecursively(root)

    @Test
    fun `walkFiles finds files at every path length`() {
        // Windows' MAX_PATH is 260 including the terminator: 259 used to be the longest path found.
        val lengths = listOf(200, 259, 260, 261, 312, 600)
        val files = lengths.map { file(pathOfLength(it)) }

        val found = walkFiles(root) { path, error -> throw AssertionError("unreadable $path", error) }.toSet()

        assertEquals(files.toSet(), found)
        assertEquals(lengths, files.map { it.toString().length })
    }

    @Test
    fun `walkFiles yields a single file root`() {
        val file = file(pathOfLength(300))

        assertEquals(listOf(file), walkFiles(file).toList())
    }

    @Test
    fun `walkFiles reports a path it can't read`() {
        // No file system accepts a name this long: Linux fails with ENAMETOOLONG, Windows with an invalid-name error.
        val unreadable = Path(root, "x".repeat(300))
        val reported = mutableListOf<Path>()

        val found = walkFiles(unreadable) { path, _ -> reported += path }.toList()

        assertTrue(found.isEmpty())
        assertEquals(listOf(unreadable), reported)
    }

    @Test
    fun `walkFiles yields nothing for a missing root`() {
        assertTrue(walkFiles(Path(root, "missing")) { path, error -> throw AssertionError("unreadable $path", error) }.none())
    }

    @Test
    fun `walkFiles visits each directory in name order whatever order it is listed in`() {
        // Listed the way FAT32 or ext4 might: write order or hash order, not by name.
        val fileSystem = ListedFileSystem(
            "show" to listOf("Show.S01E10.mkv", "Season 2", "Show.S01E02.mkv", "extras", "show.S01E01.mkv"),
            "show/Season 2" to listOf("Show.S02E01.mkv", "Show.S02E01.REPACK.mkv"),
            "show/extras" to listOf("b.mkv", "A.mkv"),
        )

        val found = walkFiles(Path("show"), fileSystem).map { it.toString().replace('\\', '/') }.toList()

        assertEquals(
            listOf(
                "show/extras/A.mkv",
                "show/extras/b.mkv",
                "show/Season 2/Show.S02E01.mkv",
                "show/Season 2/Show.S02E01.REPACK.mkv",
                "show/show.S01E01.mkv",
                "show/Show.S01E02.mkv",
                "show/Show.S01E10.mkv",
            ),
            found,
        )
    }

    @Test
    fun `FileNameOrder ignores case and compares numbers by value`() {
        val sorted = listOf("Show - 10.mkv", "show - 2.mkv", "Show - 02.mkv", "Show - 1.mkv", "Show.mkv", "Show - 9B.mkv", "Show - 9a.mkv")
            .sortedWith(FileNameOrder)

        // "Show - 02" and "show - 2" are equal but for case and padding, and fall back to plain string order.
        assertEquals(listOf("Show - 1.mkv", "Show - 02.mkv", "show - 2.mkv", "Show - 9a.mkv", "Show - 9B.mkv", "Show - 10.mkv", "Show.mkv"), sorted)
    }

    @Test
    fun `FileNameOrder puts a release before its tagged versions`() {
        val sorted = listOf("Movie.2010.REPACK.mkv", "Movie.2010.mkv", "Movie.2010.PROPER.mkv").sortedWith(FileNameOrder)

        assertEquals(listOf("Movie.2010.mkv", "Movie.2010.PROPER.mkv", "Movie.2010.REPACK.mkv"), sorted)
    }

    @Test
    fun `resolveSymbolicLink follows a chain of links`() {
        val real = file(Path(root, "real.mkv"))
        val first = Path(root, "first.mkv")
        val second = Path(root, "links", "second.mkv").also { PlatformFileSystem.createDirectories(it.parent!!) }
        if (!createSymbolicLink(first, "real.mkv") || !createSymbolicLink(second, "../first.mkv")) return

        val resolved = assertNotNull(resolveSymbolicLink(second))

        assertEquals(real.name, resolved.name)
        assertTrue(isSameFile(real, resolved))
        assertNull(resolveSymbolicLink(real))
    }

    @Test
    fun `resolveSymbolicLink fails on a dangling link`() {
        val link = Path(root, "dangling.mkv")
        if (!createSymbolicLink(link, "missing.mkv")) return

        assertFailsWith<IOException> { resolveSymbolicLink(link) }
    }

    @Test
    fun `delete removes a symlink but not its target`() {
        val real = file(Path(root, "real.mkv"))
        val link = Path(root, "link.mkv")
        val dangling = Path(root, "dangling.mkv")
        if (!createSymbolicLink(link, "real.mkv") || !createSymbolicLink(dangling, "missing.mkv")) return

        PlatformFileSystem.delete(link)
        PlatformFileSystem.delete(dangling)

        assertEquals(listOf(real), PlatformFileSystem.list(root))
    }

    /** A media file path under [root] exactly [length] characters long, in nested directories of at most 100. */
    private fun pathOfLength(length: Int): Path {
        val fileName = "S01E01.mkv"
        var path = Path(root, "len$length")
        while (length - path.toString().length - 1 > 101 + fileName.length) path = Path(path, "d".repeat(100))
        val padding = length - path.toString().length - 1 - fileName.length
        return Path(path, "x".repeat(padding) + fileName)
    }

    /** kotlinx-io can't open Windows paths past MAX_PATH, so the file is written next to [root] and moved into place. */
    private fun file(path: Path): Path {
        path.parent?.let { PlatformFileSystem.createDirectories(it) }
        val staging = Path(root, "staging.tmp")
        SystemFileSystem.sink(staging).buffered().use { it.writeString("") }
        PlatformFileSystem.atomicMove(staging, path)
        return path
    }

    /** Directories (by `/`-separated path) listing their entries in the given order; any other path is a file. */
    private class ListedFileSystem(vararg directories: Pair<String, List<String>>) : MediaFileSystem {
        private val directories = directories.toMap()

        override fun kind(path: Path) = if (key(path) in directories) FileKind.DIRECTORY else FileKind.REGULAR_FILE
        override fun list(directory: Path) = directories.getValue(key(directory)).map { Path(directory, it) }
        override fun createDirectories(path: Path) = throw UnsupportedOperationException()
        override fun atomicMove(source: Path, destination: Path) = throw UnsupportedOperationException()
        override fun delete(path: Path) = throw UnsupportedOperationException()

        private fun key(path: Path) = path.toString().replace('\\', '/')
    }

    private fun deleteRecursively(path: Path) {
        if (PlatformFileSystem.kind(path) == FileKind.DIRECTORY) {
            PlatformFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        PlatformFileSystem.delete(path)
    }
}
