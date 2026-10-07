package io.github.hospes.plexify.core

import kotlinx.cinterop.*
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.windows.*

@OptIn(ExperimentalForeignApi::class)
actual fun createHardLink(source: Path, destination: Path) {
    memScoped {
        // CreateHardLinkW fails with ERROR_ALREADY_EXISTS rather than replacing an existing destination.
        val result = CreateHardLinkW(destination.toString(), source.toString(), null)
        if (result == 0) { // If the function fails, the return value is zero.
            val errorCode = GetLastError()
            val messageBuffer = alloc<LPWSTRVar>()
            FormatMessageW(
                (FORMAT_MESSAGE_FROM_SYSTEM or FORMAT_MESSAGE_ALLOCATE_BUFFER).toUInt(),
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
            val error = "$errorMessage (error $errorCode)"
            throw IOException(if (errorCode == ERROR_NOT_SAME_DEVICE.toUInt()) error + CROSS_VOLUME_HINT else error)
        }
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
    // No access rights are needed to query file information; backup semantics also allows opening directories.
    val handle = CreateFileW(
        path.toString(),
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
