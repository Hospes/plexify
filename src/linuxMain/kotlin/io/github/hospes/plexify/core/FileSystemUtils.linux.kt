package io.github.hospes.plexify.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.errno
import platform.posix.link
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
actual fun createHardLink(source: Path, destination: Path) {
    // Check if the destination file already exists.
    if (SystemFileSystem.exists(destination)) {
        println("  -> Destination '$destination' already exists. Deleting it to create a new hardlink.")
        try {
            // If it exists, delete it.
            SystemFileSystem.delete(destination)
        } catch (e: Exception) {
            // Provide a more specific error if deletion fails (e.g., due to permissions).
            throw Exception("Failed to delete existing file at '$destination' before creating hardlink.", e)
        }
    }

    val result = link(source.toString(), destination.toString())
    if (result != 0) {
        val error = strerror(errno)?.toKString()
        throw Exception("Failed to create hardlink from '$source' to '$destination'. Error: $error")
    }
}