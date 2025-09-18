package io.github.hospes.plexify.core

import kotlinx.cinterop.*
import kotlinx.io.files.Path
import platform.windows.*

@OptIn(ExperimentalForeignApi::class)
actual fun createHardLink(source: Path, destination: Path) {
    memScoped {
        // Now these functions (CreateHardLinkW, GetLastError, etc.) will be resolved
        // because they are imported from the 'winfileapi' package.
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
            val errorMessage = messageBuffer.value?.toKString() ?: "Unknown error"
            // Don't forget to free the buffer allocated by FormatMessageW
            LocalFree(messageBuffer.value)
            throw Exception("Failed to create hardlink from '$source' to '$destination'. Error ($errorCode): $errorMessage")
        }
    }
}