package io.github.hospes.plexify.core

import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import platform.windows.ERROR_ACCESS_DENIED
import platform.windows.ERROR_BAD_NETPATH
import platform.windows.ERROR_LOGON_FAILURE
import platform.windows.ERROR_SEM_TIMEOUT
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemUtilsWindowsTest {

    @Test
    fun `a Win32 failure names its error code`() {
        val missing = Path(SystemTemporaryDirectory, "plexify-missing-${Random.nextLong().toULong()}.mkv")

        val error = assertFailsWith<IOException> { PlatformFileSystem.atomicMove(missing, Path("$missing.moved")) }

        // ERROR_FILE_NOT_FOUND: the message alone is localized, and an unexpected one is otherwise hard to look up.
        assertTrue(error.message.orEmpty().endsWith("(error 2)"), error.message)
    }

    @Test
    fun `a link target behind an offline share is unreachable but one denied to the user is not`() {
        assertTrue(isUnreachableTarget(ERROR_BAD_NETPATH.toUInt()))
        assertTrue(isUnreachableTarget(ERROR_SEM_TIMEOUT.toUInt()))
        assertFalse(isUnreachableTarget(ERROR_ACCESS_DENIED.toUInt()))
        assertFalse(isUnreachableTarget(ERROR_LOGON_FAILURE.toUInt()))
    }
}
