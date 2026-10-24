package io.github.hospes.plexify.core

import io.github.hospes.plexify.win32.*
import kotlinx.cinterop.*
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.windows.*

@OptIn(ExperimentalForeignApi::class)
actual fun createHardLink(source: Path, destination: Path) {
    // CreateHardLinkW fails with ERROR_ALREADY_EXISTS rather than replacing an existing destination. Given a symlink,
    // it links the symlink itself, so the link is resolved first.
    val existing = resolveSymbolicLink(source) ?: source
    val errorCode = plexify_create_hard_link(win32Path(destination), win32Path(existing))
    if (errorCode != 0u) {
        val error = "${win32ErrorMessage(errorCode)} (error $errorCode)"
        throw IOException(if (errorCode == ERROR_NOT_SAME_DEVICE.toUInt()) error + CROSS_VOLUME_HINT else error)
    }
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

@OptIn(ExperimentalForeignApi::class)
actual fun resolveSymbolicLink(path: Path): Path? = memScoped {
    val win32Path = win32Path(path)
    val tag = alloc<DWORDVar>()
    var errorCode = plexify_get_reparse_tag(win32Path, tag.ptr)
    if (errorCode != 0u) throw win32Error("Can't read '$path'", errorCode)
    // Other reparse points (OneDrive placeholders, deduplicated files) are files in their own right.
    if (tag.value != IO_REPARSE_TAG_SYMLINK) return null

    val length = alloc<DWORDVar>()
    errorCode = plexify_get_final_path_name(win32Path, 0u, null, length.ptr)
    if (errorCode != 0u) throw win32Error("Can't resolve symlink '$path'", errorCode)
    val buffer = allocArray<WCHARVar>(length.value.toInt())
    errorCode = plexify_get_final_path_name(win32Path, length.value, buffer, length.ptr)
    if (errorCode != 0u) throw win32Error("Can't resolve symlink '$path'", errorCode)
    // win32Path adds the prefix back where it is needed.
    val final = buffer.toKStringFromUtf16()
    Path(if (final.startsWith("\\\\?\\UNC\\")) "\\\\" + final.removePrefix("\\\\?\\UNC\\") else final.removePrefix("\\\\?\\"))
}

actual val PlatformFileSystem: MediaFileSystem = WindowsFileSystem

private val TARGET_UNREACHABLE = setOf(
    ERROR_FILE_NOT_FOUND, ERROR_PATH_NOT_FOUND,
    ERROR_BAD_NETPATH, ERROR_BAD_NET_NAME, ERROR_REM_NOT_LIST, ERROR_DEV_NOT_EXIST, ERROR_UNEXP_NET_ERR,
    ERROR_NETNAME_DELETED, ERROR_SEM_TIMEOUT, ERROR_CONNECTION_UNAVAIL, ERROR_NO_NET_OR_BAD_PATH, ERROR_NO_NETWORK,
    ERROR_CONNECTION_REFUSED, ERROR_NETWORK_UNREACHABLE, ERROR_HOST_UNREACHABLE, ERROR_CONNECTION_ABORTED,
    ERROR_HOST_DOWN, ERROR_NOT_CONNECTED,
).mapTo(mutableSetOf()) { it.toUInt() }

/**
 * Whether opening a link's target failed with [errorCode] because it can't be reached: it doesn't exist, or it is on
 * a network share whose host doesn't resolve, is offline or doesn't answer in time. Which error a UNC path gets
 * depends on the network: an unknown host is usually ERROR_BAD_NETPATH, but not always. Errors such as access denied
 * or a failed logon are not among them: the target is there, and the user should see why it can't be read.
 */
internal fun isUnreachableTarget(errorCode: UInt): Boolean = errorCode in TARGET_UNREACHABLE

@OptIn(ExperimentalForeignApi::class)
private object WindowsFileSystem : MediaFileSystem {
    override fun kind(path: Path): FileKind? = memScoped {
        val win32Path = win32Path(path)
        var attributes = entryAttributes(win32Path, path) ?: return null
        // GetFileAttributesW reads a symlink or junction itself, so a file symlink would read as a regular file even
        // when its target is gone. Like stat on Linux, follow it.
        if (attributes and FILE_ATTRIBUTE_REPARSE_POINT.toUInt() != 0u && isLink(win32Path, path)) {
            val target = alloc<DWORDVar>()
            val errorCode = plexify_get_target_attributes(win32Path, target.ptr)
            when {
                errorCode == 0u -> attributes = target.value
                isUnreachableTarget(errorCode) -> return FileKind.DANGLING_LINK
                else -> throw win32Error("Can't read '$path'", errorCode)
            }
        }
        if (attributes and FILE_ATTRIBUTE_DIRECTORY.toUInt() != 0u) FileKind.DIRECTORY else FileKind.REGULAR_FILE
    }

    /** The attributes of the entry at [win32Path] itself, not following a link, or null if there is none. */
    private fun entryAttributes(win32Path: String, path: Path): UInt? = memScoped {
        val attributes = alloc<DWORDVar>()
        when (val errorCode = plexify_get_file_attributes(win32Path, attributes.ptr)) {
            0u -> attributes.value
            ERROR_FILE_NOT_FOUND.toUInt(), ERROR_PATH_NOT_FOUND.toUInt() -> null
            else -> throw win32Error("Can't read '$path'", errorCode)
        }
    }

    /** Whether the reparse point at [win32Path] is a symlink or junction, rather than e.g. a OneDrive placeholder. */
    private fun isLink(win32Path: String, path: Path): Boolean = memScoped {
        val tag = alloc<DWORDVar>()
        val errorCode = plexify_get_reparse_tag(win32Path, tag.ptr)
        if (errorCode != 0u) throw win32Error("Can't read '$path'", errorCode)
        tag.value == IO_REPARSE_TAG_SYMLINK || tag.value == IO_REPARSE_TAG_MOUNT_POINT
    }

    override fun identity(path: Path): FileIdentity? = memScoped {
        val info = fileInformation(path) ?: return null
        FileIdentity(
            volume = info.dwVolumeSerialNumber.toULong(),
            file = (info.nFileIndexHigh.toULong() shl 32) or info.nFileIndexLow.toULong(),
        )
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
            FileKind.DANGLING_LINK -> throw IOException("Can't create directory '$path': it is a broken symlink")
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
        val win32Path = win32Path(path)
        // The entry itself, not what a link points at: a symlink to a directory, dangling or not, is removed as one.
        val attributes = entryAttributes(win32Path, path) ?: return
        val errorCode = if (attributes and FILE_ATTRIBUTE_DIRECTORY.toUInt() != 0u) plexify_remove_directory(win32Path)
        else plexify_delete_file(win32Path)
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

private fun win32Error(context: String, errorCode: UInt) =
    IOException("$context: ${win32ErrorMessage(errorCode)} (error $errorCode)")

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
