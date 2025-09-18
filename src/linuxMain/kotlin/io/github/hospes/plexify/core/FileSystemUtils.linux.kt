package io.github.hospes.plexify.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.errno
import platform.posix.link
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
actual fun createHardLink(source: Path, destination: Path) {
    val result = link(source.toString(), destination.toString())
    if (result != 0) {
        val error = strerror(errno)?.toKString()
        throw Exception("Failed to create hardlink from '$source' to '$destination'. Error: $error")
    }
}