package io.github.hospes.plexify.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.posix.EXDEV
import platform.posix.errno
import platform.posix.link
import platform.posix.stat
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
actual fun createHardLink(source: Path, destination: Path) {
    // link() fails with EEXIST rather than replacing an existing destination.
    if (link(source.toString(), destination.toString()) != 0) {
        val code = errno
        val error = strerror(code)?.toKString() ?: "errno $code"
        throw IOException(if (code == EXDEV) error + CROSS_VOLUME_HINT else error)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun isSameFile(first: Path, second: Path): Boolean = memScoped {
    val firstStat = alloc<stat>()
    val secondStat = alloc<stat>()
    if (stat(first.toString(), firstStat.ptr) != 0 || stat(second.toString(), secondStat.ptr) != 0) return false
    firstStat.st_dev == secondStat.st_dev && firstStat.st_ino == secondStat.st_ino
}
