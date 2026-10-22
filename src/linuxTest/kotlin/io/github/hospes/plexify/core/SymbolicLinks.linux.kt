package io.github.hospes.plexify.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.posix.symlink

@OptIn(ExperimentalForeignApi::class)
actual fun createSymbolicLink(link: Path, target: String): Boolean {
    check(symlink(target, link.toString()) == 0) { "Can't create symlink '$link'" }
    println("PROBE symlink created=true link=${link.name}")
    return true
}
