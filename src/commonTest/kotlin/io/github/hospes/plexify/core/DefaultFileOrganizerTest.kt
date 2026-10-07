package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.service.PathFormatter
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultFileOrganizerTest {

    private fun organizer(overwrite: Boolean = false) = DefaultFileOrganizer(PathFormatter(), NamingStrategy.Jellyfin, overwrite)
    private val movie = CanonicalMedia.Movie(title = "Inception", year = 2010, tmdbId = "27205")
    private val parsed = ParsedMediaInfo.Movie(title = "Inception", year = "2010")

    private val root = Path(SystemTemporaryDirectory, "plexify-organizer-test-${Random.nextLong().toULong()}")
        .also { SystemFileSystem.createDirectories(it) }
    private val library = Path(root, "library")

    @AfterTest
    fun cleanUp() = deleteRecursively(root)

    @Test
    fun `hardlinks a new file`() {
        val source = file(Path(root, "Inception.2010.1080p.mkv"), "movie")

        val outcome = organize(source, OperationMode.HARDLINK)

        assertIs<OrganizeOutcome.Organized>(outcome)
        assertEquals("movie", read(outcome.path))
        assertTrue(isSameFile(source, outcome.path))
    }

    @Test
    fun `moves a new file`() {
        val source = file(Path(root, "Inception.2010.1080p.mkv"), "movie")

        val outcome = organize(source, OperationMode.MOVE)

        assertIs<OrganizeOutcome.Organized>(outcome)
        assertEquals("movie", read(outcome.path))
        assertFalse(SystemFileSystem.exists(source))
    }

    @Test
    fun `keeps a file whose target is its own path when hardlinking`() = assertKeepsFileAlreadyAtTarget(OperationMode.HARDLINK)

    @Test
    fun `keeps a file whose target is its own path when moving`() = assertKeepsFileAlreadyAtTarget(OperationMode.MOVE)

    @Test
    fun `keeps a file whose target is reached through a relative path`() {
        val target = targetPath()
        file(target, "movie")
        val indirect = Path(target.parent!!, "..", target.parent!!.name, target.name)

        val outcome = organize(indirect, OperationMode.HARDLINK)

        assertIs<OrganizeOutcome.AlreadyInPlace>(outcome)
        assertEquals("movie", read(target))
    }

    @Test
    fun `treats an existing hardlink to the source as already in place`() {
        val source = file(Path(root, "Inception.2010.1080p.mkv"), "movie")
        val target = targetPath()
        SystemFileSystem.createDirectories(target.parent!!)
        createHardLink(source, target)

        for (mode in OperationMode.entries) {
            assertIs<OrganizeOutcome.AlreadyInPlace>(organize(source, mode), "mode $mode")
            assertEquals("movie", read(source))
            assertEquals("movie", read(target))
        }
    }

    @Test
    fun `leaves a different file at the target untouched`() {
        val source = file(Path(root, "Inception.2010.1080p.mkv"), "new release")
        val target = file(targetPath(), "existing copy")

        for (mode in OperationMode.entries) {
            assertIs<OrganizeOutcome.TargetExists>(organize(source, mode), "mode $mode")
            assertEquals("new release", read(source))
            assertEquals("existing copy", read(target))
        }
    }

    @Test
    fun `reports a conflict in test mode without touching files`() {
        val source = file(Path(root, "Inception.2010.1080p.mkv"), "new release")
        val target = file(targetPath(), "existing copy")

        assertIs<OrganizeOutcome.TargetExists>(organize(source, OperationMode.HARDLINK, isTestMode = true))
        assertEquals("existing copy", read(target))
    }

    @Test
    fun `overwrite replaces a different file with a hardlink to the source`() {
        val source = file(Path(root, "Inception.2010.1080p.mkv"), "new release")
        val target = file(targetPath(), "existing copy")

        val outcome = organize(source, OperationMode.HARDLINK, overwrite = true)

        assertIs<OrganizeOutcome.Replaced>(outcome)
        assertEquals("new release", read(target))
        assertTrue(isSameFile(source, target))
        assertEquals(listOf(target.name), SystemFileSystem.list(target.parent!!).map { it.name })
    }

    @Test
    fun `overwrite replaces a different file when moving`() {
        val source = file(Path(root, "Inception.2010.1080p.mkv"), "new release")
        val target = file(targetPath(), "existing copy")

        val outcome = organize(source, OperationMode.MOVE, overwrite = true)

        assertIs<OrganizeOutcome.Replaced>(outcome)
        assertEquals("new release", read(target))
        assertFalse(SystemFileSystem.exists(source))
    }

    @Test
    fun `overwrite reports the replacement in test mode without touching files`() {
        val source = file(Path(root, "Inception.2010.1080p.mkv"), "new release")
        val target = file(targetPath(), "existing copy")

        assertIs<OrganizeOutcome.Replaced>(organize(source, OperationMode.HARDLINK, isTestMode = true, overwrite = true))
        assertEquals("existing copy", read(target))
    }

    @Test
    fun `overwrite never replaces the source itself`() {
        val target = file(targetPath(), "movie")
        val source = Path(root, "Inception.2010.1080p.mkv").also { createHardLink(target, it) }

        for (mode in OperationMode.entries) {
            assertIs<OrganizeOutcome.AlreadyInPlace>(organize(target, mode, overwrite = true), "own path, mode $mode")
            assertIs<OrganizeOutcome.AlreadyInPlace>(organize(source, mode, overwrite = true), "hardlink, mode $mode")
            assertEquals("movie", read(target))
            assertEquals("movie", read(source))
        }
    }

    @Test
    fun `overwrite replaces a target whose path is close to the Windows length limit`() {
        // MAX_PATH is 260 including the terminator; a target just under it must stay replaceable,
        // so the temporary link name can't be much longer than the target's own name.
        val probe = targetPath(library).toString().length
        val deepLibrary = Path(root, "x".repeat(255 - probe), "library")
        val source = file(Path(root, "Inception.2010.1080p.mkv"), "new release")
        val target = file(targetPath(deepLibrary), "existing copy")
        assertEquals(256, target.toString().length)

        val outcome = organizer(overwrite = true)
            .organize(source, deepLibrary, movie, parsed, OperationMode.HARDLINK, isTestMode = false).getOrThrow()

        assertIs<OrganizeOutcome.Replaced>(outcome)
        assertEquals("new release", read(target))
    }

    @Test
    fun `organizes between paths past the Windows length limit`() {
        val library = Path(longDirectory("library"), "library")

        for (mode in OperationMode.entries) {
            val source = file(Path(longDirectory("source-$mode"), "Inception.2010.1080p.mkv"), "movie")
            val target = targetPath(library)
            assertTrue(source.toString().length > 300 && target.toString().length > 300, "paths too short: $target")

            val outcome = organizer().organize(source, library, movie, parsed, mode, isTestMode = false).getOrThrow()

            assertIs<OrganizeOutcome.Organized>(outcome, "mode $mode")
            assertEquals(FileKind.REGULAR_FILE, PlatformFileSystem.kind(target), "mode $mode")
            when (mode) {
                OperationMode.HARDLINK -> assertTrue(isSameFile(source, target))
                OperationMode.MOVE -> assertNull(PlatformFileSystem.kind(source))
            }
            assertIs<OrganizeOutcome.TargetExists>(
                organizer().organize(file(Path(root, "other.mkv"), "other"), library, movie, parsed, mode, isTestMode = false).getOrThrow(),
                "mode $mode",
            )
            deleteRecursively(library)
        }
    }

    @Test
    fun `overwrite replaces a target past the Windows length limit`() {
        val library = Path(longDirectory("library"), "library")
        val source = file(Path(root, "Inception.2010.1080p.mkv"), "new release")
        val target = file(targetPath(library), "existing copy")

        val outcome = organizer(overwrite = true)
            .organize(source, library, movie, parsed, OperationMode.HARDLINK, isTestMode = false).getOrThrow()

        assertIs<OrganizeOutcome.Replaced>(outcome)
        assertTrue(isSameFile(source, target))
        assertEquals(listOf(target.name), PlatformFileSystem.list(target.parent!!).map { it.name })
    }

    @Test
    fun `overwrite keeps the existing file when the hardlink fails`() {
        // Neither platform can hardlink a directory, so the link step fails after the checks pass.
        val unlinkable = Path(root, "Inception.2010.1080p.mkv").also { SystemFileSystem.createDirectories(it) }
        val target = file(targetPath(), "existing copy")

        val error = organizer(overwrite = true)
            .organize(unlinkable, library, movie, parsed, OperationMode.HARDLINK, isTestMode = false)
            .exceptionOrNull()

        assertTrue(error?.message?.startsWith("Hardlink failed: ") == true, "got $error")
        assertEquals("existing copy", read(target))
        assertEquals(listOf(target.name), SystemFileSystem.list(target.parent!!).map { it.name })
    }

    @Test
    fun `hardlink failure carries the platform error`() {
        val missing = Path(root, "Inception.2010.1080p.mkv")

        val error = organizer().organize(missing, library, movie, parsed, OperationMode.HARDLINK, isTestMode = false)
            .exceptionOrNull()

        val cause = error?.cause?.message
        assertTrue(cause != null && cause.isNotBlank(), "expected a platform error, got $error")
        assertEquals("Hardlink failed: $cause", error.message)
    }

    @Test
    fun `isSameFile tells hardlinks from copies`() {
        val original = file(Path(root, "a.mkv"), "same bytes")
        val copy = file(Path(root, "b.mkv"), "same bytes")
        val link = Path(root, "c.mkv").also { createHardLink(original, it) }

        assertTrue(isSameFile(original, original))
        assertTrue(isSameFile(original, link))
        assertFalse(isSameFile(original, copy))
        assertFalse(isSameFile(original, Path(root, "missing.mkv")))
    }

    @Test
    fun `hardlinks the file a relative symlink points at`() {
        val real = file(Path(root, "downloads", "Inception.2010.1080p.mkv"), "movie")
        val source = symlink(Path(root, "links", real.name), "../downloads/${real.name}") ?: return

        val outcome = organize(source, OperationMode.HARDLINK)

        assertIs<OrganizeOutcome.Organized>(outcome)
        assertEquals("movie", read(outcome.path))
        assertTrue(isSameFile(real, outcome.path))
        assertNull(resolveSymbolicLink(outcome.path), "the library got the symlink itself")
        assertTrue(isSameFile(source, real), "the source symlink changed")
    }

    @Test
    fun `overwrite replaces a different file with a hardlink to the file a symlink points at`() {
        val real = file(Path(root, "downloads", "Inception.2010.1080p.mkv"), "new release")
        val source = symlink(Path(root, "links", real.name), "../downloads/${real.name}") ?: return
        val target = file(targetPath(), "existing copy")

        val outcome = organize(source, OperationMode.HARDLINK, overwrite = true)

        assertIs<OrganizeOutcome.Replaced>(outcome)
        assertTrue(isSameFile(real, target))
        assertNull(resolveSymbolicLink(target), "the library got the symlink itself")
        assertEquals(listOf(target.name), PlatformFileSystem.list(target.parent!!).map { it.name })
    }

    @Test
    fun `moves a symlink that still resolves from the library`() {
        val real = file(Path(root, "downloads", "Inception.2010.1080p.mkv"), "movie")
        val source = symlink(Path(root, "links", real.name), absolute(real)) ?: return

        val outcome = organize(source, OperationMode.MOVE)

        assertIs<OrganizeOutcome.Organized>(outcome)
        assertNotNull(resolveSymbolicLink(outcome.path), "expected the symlink itself in the library")
        assertTrue(isSameFile(real, outcome.path))
        assertNull(PlatformFileSystem.kind(source))
        assertEquals("movie", read(real))
        assertEquals(listOf(outcome.path.name), PlatformFileSystem.list(outcome.path.parent!!).map { it.name })
    }

    @Test
    fun `refuses to move a relative symlink that would not resolve from the library`() {
        val real = file(Path(root, "downloads", "Inception.2010.1080p.mkv"), "movie")
        val source = symlink(Path(root, "links", real.name), "../downloads/${real.name}") ?: return
        val target = targetPath()

        for (overwrite in listOf(false, true)) {
            if (overwrite) file(target, "existing copy")

            val error = organizer(overwrite).organize(source, library, movie, parsed, OperationMode.MOVE, isTestMode = false)
                .exceptionOrNull()

            assertTrue(error?.message?.contains("won't resolve from the library") == true, "got $error")
            assertTrue(isSameFile(source, real), "the source symlink wasn't put back")
            val left = PlatformFileSystem.list(target.parent!!).map { it.name }
            if (overwrite) {
                assertEquals("existing copy", read(target))
                assertEquals(listOf(target.name), left)
            } else {
                assertEquals(emptyList(), left)
            }
        }
    }

    @Test
    fun `treats a symlink to the file at the target as already in place`() {
        val target = file(targetPath(), "movie")
        val source = symlink(Path(root, "links", "Inception.2010.1080p.mkv"), absolute(target)) ?: return

        for (mode in OperationMode.entries) {
            assertIs<OrganizeOutcome.AlreadyInPlace>(organize(source, mode, overwrite = true), "mode $mode")
            assertTrue(isSameFile(source, target), "mode $mode")
            assertEquals("movie", read(target))
        }
    }

    private fun assertKeepsFileAlreadyAtTarget(mode: OperationMode) {
        val target = file(targetPath(), "movie")

        val outcome = organize(target, mode)

        assertIs<OrganizeOutcome.AlreadyInPlace>(outcome)
        assertEquals(target, outcome.path)
        assertEquals("movie", read(target))
    }

    /** Where the organizer files the test movie, from a dry run against a library that has nothing in it yet. */
    private fun targetPath(library: Path = this.library): Path =
        organizer().organize(Path(root, "probe.mkv"), library, movie, parsed, OperationMode.HARDLINK, isTestMode = true)
            .getOrThrow().path

    private fun organize(source: Path, mode: OperationMode, isTestMode: Boolean = false, overwrite: Boolean = false): OrganizeOutcome =
        organizer(overwrite).organize(source, library, movie, parsed, mode, isTestMode).getOrThrow()

    /** Writes [content] to [path]. kotlinx-io can't open Windows paths past MAX_PATH, so the file is moved into place. */
    private fun file(path: Path, content: String): Path {
        path.parent?.let { PlatformFileSystem.createDirectories(it) }
        val staging = Path(root, "staging.tmp")
        SystemFileSystem.sink(staging).buffered().use { it.writeString(content) }
        PlatformFileSystem.atomicMove(staging, path)
        return path
    }

    /** A symlink at [link] to [target] (see [createSymbolicLink]), or null where symlinks can't be created. */
    private fun symlink(link: Path, target: String): Path? {
        link.parent?.let { PlatformFileSystem.createDirectories(it) }
        if (createSymbolicLink(link, target)) return link
        println("Skipped: this system doesn't allow creating symlinks")
        return null
    }

    /** [path] as an absolute path. [root] is relative on Linux when TMPDIR is unset, as on the CI runners. */
    private fun absolute(path: Path): String = SystemFileSystem.resolve(path).toString()

    private fun read(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

    /** A directory under [root] deep enough that a library path inside it is well past Windows' MAX_PATH. */
    private fun longDirectory(name: String): Path =
        Path(root, *Array(4) { "${name}-${"x".repeat(80)}" })

    private fun deleteRecursively(path: Path) {
        if (PlatformFileSystem.kind(path) == FileKind.DIRECTORY) {
            PlatformFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        PlatformFileSystem.delete(path)
    }
}
