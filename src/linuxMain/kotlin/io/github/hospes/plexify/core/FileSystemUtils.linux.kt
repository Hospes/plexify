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
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.errno
import platform.posix.free
import platform.posix.link
import platform.posix.lstat
import platform.posix.realpath
import platform.posix.remove
import platform.posix.rename
import platform.posix.stat
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
actual fun createHardLink(source: Path, destination: Path) {
    // link() fails with EEXIST rather than replacing an existing destination. Given a symlink, it links the symlink
    // itself, so the link is resolved first. (linkat with AT_SYMLINK_FOLLOW isn't in Kotlin/Native's Linux sysroot.)
    val existing = resolveSymbolicLink(source) ?: source
    if (link(existing.toString(), destination.toString()) != 0) {
        val code = errno
        val error = errorMessage(code)
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

@OptIn(ExperimentalForeignApi::class)
actual fun resolveSymbolicLink(path: Path): Path? = memScoped {
    val pathStat = alloc<stat>()
    if (lstat(path.toString(), pathStat.ptr) != 0) throw IOException("Can't read '$path': ${errorMessage(errno)}")
    if (pathStat.st_mode.toInt() and S_IFMT != S_IFLNK) return null
    val resolved = realpath(path.toString(), null)
        ?: throw IOException("Can't resolve symlink '$path': ${errorMessage(errno)}")
    try {
        Path(resolved.toKString())
    } finally {
        free(resolved)
    }
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
            throw IOException("Can't read '$path': ${errorMessage(code)}")
        }
        when (pathStat.st_mode.toInt() and S_IFMT) {
            S_IFREG -> FileKind.REGULAR_FILE
            S_IFDIR -> FileKind.DIRECTORY
            else -> FileKind.OTHER
        }
    }

    override fun list(directory: Path): List<Path> = SystemFileSystem.list(directory).toList()

    override fun createDirectories(path: Path) = SystemFileSystem.createDirectories(path, mustCreate = false)

    override fun atomicMove(source: Path, destination: Path) {
        // kotlinx-io first checks that the source exists, following a symlink, so it refuses to move a dangling one.
        if (rename(source.toString(), destination.toString()) != 0) {
            throw IOException("Can't move '$source' to '$destination': ${errorMessage(errno)}")
        }
    }

    override fun delete(path: Path) {
        // kotlinx-io skips a dangling symlink as missing; remove() deletes any link itself.
        if (remove(path.toString()) != 0) {
            val code = errno
            if (code != ENOENT) throw IOException("Can't delete '$path': ${errorMessage(code)}")
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun errorMessage(code: Int): String = strerror(code)?.toKString() ?: "errno $code"
