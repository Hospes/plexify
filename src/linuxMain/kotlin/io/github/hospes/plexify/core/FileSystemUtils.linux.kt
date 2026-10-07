package io.github.hospes.plexify.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.EXDEV
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
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

actual val PlatformFileSystem: MediaFileSystem = LinuxFileSystem

private object LinuxFileSystem : MediaFileSystem {
    @OptIn(ExperimentalForeignApi::class)
    override fun kind(path: Path): FileKind? = memScoped {
        // kotlinx-io's metadataOrNull can't tell a missing path from an unreadable one.
        val pathStat = alloc<stat>()
        if (stat(path.toString(), pathStat.ptr) != 0) {
            val code = errno
            if (code == ENOENT || code == ENOTDIR) return null
            throw IOException("Can't read '$path': ${strerror(code)?.toKString() ?: "errno $code"}")
        }
        when (pathStat.st_mode.toInt() and S_IFMT) {
            S_IFREG -> FileKind.REGULAR_FILE
            S_IFDIR -> FileKind.DIRECTORY
            else -> FileKind.OTHER
        }
    }

    override fun list(directory: Path): List<Path> = SystemFileSystem.list(directory).toList()

    override fun createDirectories(path: Path) = SystemFileSystem.createDirectories(path, mustCreate = false)

    override fun atomicMove(source: Path, destination: Path) = SystemFileSystem.atomicMove(source, destination)

    override fun delete(path: Path) = SystemFileSystem.delete(path, mustExist = false)
}
