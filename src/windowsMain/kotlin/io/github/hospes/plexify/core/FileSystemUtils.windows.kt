package io.github.hospes.plexify.core

import io.github.hospes.plexify.win32.*
import kotlinx.cinterop.*
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.windows.*

@OptIn(ExperimentalForeignApi::class)
actual fun createHardLink(source: Path, destination: Path) {
    // CreateHardLinkW fails with ERROR_ALREADY_EXISTS rather than replacing an existing destination.
    val errorCode = plexify_create_hard_link(win32Path(destination), win32Path(source))
    if (errorCode != 0u) {
        val error = "${win32ErrorMessage(errorCode)} (error $errorCode)"
        throw IOException(if (errorCode == ERROR_NOT_SAME_DEVICE.toUInt()) error + CROSS_VOLUME_HINT else error)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun isSameFile(first: Path, second: Path): Boolean = memScoped {
    val firstInfo = fileInformation(first) ?: return false
    val secondInfo = fileInformation(second) ?: return false
    firstInfo.dwVolumeSerialNumber == secondInfo.dwVolumeSerialNumber &&
        firstInfo.nFileIndexHigh == secondInfo.nFileIndexHigh &&
        firstInfo.nFileIndexLow == secondInfo.nFileIndexLow
}

/** Volume serial and file index identify a file across all of its hardlinks. Null if [path] can't be opened. */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.fileInformation(path: Path): BY_HANDLE_FILE_INFORMATION? {
    val win32Path = try {
        win32Path(path)
    } catch (_: IOException) {
        return null
    }
    // No access rights are needed to query file information; backup semantics also allows opening directories.
    val handle = CreateFileW(
        win32Path,
        0u,
        (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).toUInt(),
        null,
        OPEN_EXISTING.toUInt(),
        FILE_FLAG_BACKUP_SEMANTICS.toUInt(),
        null,
    )
    if (handle == INVALID_HANDLE_VALUE) return null
    try {
        val info = alloc<BY_HANDLE_FILE_INFORMATION>()
        return if (GetFileInformationByHandle(handle, info.ptr) != 0) info else null
    } finally {
        CloseHandle(handle)
    }
}

actual val PlatformFileSystem: MediaFileSystem = WindowsFileSystem

@OptIn(ExperimentalForeignApi::class)
private object WindowsFileSystem : MediaFileSystem {
    override fun kind(path: Path): FileKind? = memScoped {
        val attributes = alloc<DWORDVar>()
        when (val errorCode = plexify_get_file_attributes(win32Path(path), attributes.ptr)) {
            0u -> Unit
            ERROR_FILE_NOT_FOUND.toUInt(), ERROR_PATH_NOT_FOUND.toUInt() -> return null
            else -> throw win32Error("Can't read '$path'", errorCode)
        }
        // Like stat on Linux, this follows a symlink or junction to a directory.
        if (attributes.value and FILE_ATTRIBUTE_DIRECTORY.toUInt() != 0u) FileKind.DIRECTORY else FileKind.REGULAR_FILE
    }

    override fun list(directory: Path): List<Path> = memScoped {
        val data = alloc<WIN32_FIND_DATAW>()
        val handle = alloc<HANDLEVar>()
        when (val errorCode = plexify_find_first_file(win32Path(directory) + "\\*", data.ptr, handle.ptr)) {
            0u -> Unit
            ERROR_FILE_NOT_FOUND.toUInt() -> return emptyList() // Only for a drive root with no entries.
            else -> throw win32Error("Can't list '$directory'", errorCode)
        }
        try {
            buildList {
                do {
                    val name = data.cFileName.toKStringFromUtf16()
                    if (name != "." && name != "..") add(Path(directory, name))
                    val errorCode = plexify_find_next_file(handle.value, data.ptr)
                    if (errorCode != 0u && errorCode != ERROR_NO_MORE_FILES.toUInt()) {
                        throw win32Error("Can't list '$directory'", errorCode)
                    }
                } while (errorCode == 0u)
            }
        } finally {
            FindClose(handle.value)
        }
    }

    override fun createDirectories(path: Path) {
        when (kind(path)) {
            FileKind.DIRECTORY -> return
            null -> path.parent?.let { createDirectories(it) }
            else -> throw IOException("Can't create directory '$path': a file with that name exists")
        }
        val errorCode = plexify_create_directory(win32Path(path))
        // Another process may have created it in the meantime.
        if (errorCode != 0u && (errorCode != ERROR_ALREADY_EXISTS.toUInt() || kind(path) != FileKind.DIRECTORY)) {
            throw win32Error("Can't create directory '$path'", errorCode)
        }
    }

    override fun atomicMove(source: Path, destination: Path) {
        val errorCode = plexify_move_file_replacing(win32Path(source), win32Path(destination))
        if (errorCode != 0u) throw win32Error("Can't move '$source' to '$destination'", errorCode)
    }

    override fun delete(path: Path) {
        val errorCode = when (kind(path)) {
            null -> return
            FileKind.DIRECTORY -> plexify_remove_directory(win32Path(path))
            else -> plexify_delete_file(win32Path(path))
        }
        if (errorCode != 0u) throw win32Error("Can't delete '$path'", errorCode)
    }
}

/**
 * [path] as an absolute `\\?\` path. The wide-character Win32 functions accept these past MAX_PATH (260 characters)
 * without the system-wide LongPathsEnabled setting, but they skip all normalization, so the path is made absolute
 * and normalized (`.`, `..`, `/`) first. GetFullPathNameW has no length limit.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun win32Path(path: Path): String {
    val raw = path.toString()
    if (raw.startsWith("\\\\?\\") || raw.startsWith("\\\\.\\")) return raw
    val full = memScoped {
        val length = alloc<DWORDVar>()
        var errorCode = plexify_get_full_path_name(raw, 0u, null, length.ptr)
        if (errorCode != 0u) throw win32Error("Can't resolve '$raw'", errorCode)
        val buffer = allocArray<WCHARVar>(length.value.toInt())
        errorCode = plexify_get_full_path_name(raw, length.value, buffer, length.ptr)
        if (errorCode != 0u) throw win32Error("Can't resolve '$raw'", errorCode)
        buffer.toKStringFromUtf16()
    }
    return if (full.startsWith("\\\\")) "\\\\?\\UNC\\" + full.removePrefix("\\\\") else "\\\\?\\$full"
}

private fun win32Error(context: String, errorCode: UInt) = IOException("$context: ${win32ErrorMessage(errorCode)}")

@OptIn(ExperimentalForeignApi::class)
private fun win32ErrorMessage(errorCode: UInt): String = memScoped {
    val messageBuffer = alloc<LPWSTRVar>()
    FormatMessageW(
        (FORMAT_MESSAGE_FROM_SYSTEM or FORMAT_MESSAGE_ALLOCATE_BUFFER or FORMAT_MESSAGE_IGNORE_INSERTS).toUInt(),
        null,
        errorCode,
        (SUBLANG_DEFAULT shl 10 or LANG_NEUTRAL).toUInt(), // MAKELANGID
        messageBuffer.ptr.reinterpret(),
        0u,
        null
    )
    val errorMessage = messageBuffer.value?.toKString()?.trim() ?: "Unknown error"
    // Don't forget to free the buffer allocated by FormatMessageW
    LocalFree(messageBuffer.value)
    errorMessage
}
