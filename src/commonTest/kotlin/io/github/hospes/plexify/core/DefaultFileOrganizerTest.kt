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
import kotlin.test.assertTrue

class DefaultFileOrganizerTest {

    private val organizer = DefaultFileOrganizer(PathFormatter(), NamingStrategy.Jellyfin)
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
    fun `hardlink failure carries the platform error`() {
        val missing = Path(root, "Inception.2010.1080p.mkv")

        val error = organizer.organize(missing, library, movie, parsed, OperationMode.HARDLINK, isTestMode = false)
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

    private fun assertKeepsFileAlreadyAtTarget(mode: OperationMode) {
        val target = file(targetPath(), "movie")

        val outcome = organize(target, mode)

        assertIs<OrganizeOutcome.AlreadyInPlace>(outcome)
        assertEquals(target, outcome.path)
        assertEquals("movie", read(target))
    }

    /** Where the organizer files the test movie, from a dry run against a library that has nothing in it yet. */
    private fun targetPath(): Path = organize(Path(root, "probe.mkv"), OperationMode.HARDLINK, isTestMode = true).path

    private fun organize(source: Path, mode: OperationMode, isTestMode: Boolean = false): OrganizeOutcome =
        organizer.organize(source, library, movie, parsed, mode, isTestMode).getOrThrow()

    private fun file(path: Path, content: String): Path {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(path).buffered().use { it.writeString(content) }
        return path
    }

    private fun read(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

    private fun deleteRecursively(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it) }
        }
        SystemFileSystem.delete(path, mustExist = false)
    }
}
