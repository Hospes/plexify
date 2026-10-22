package io.github.hospes.plexify.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.posix.getenv
import platform.windows.CreateSymbolicLinkW

/** SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE: works in Developer Mode without administrator rights. */
private const val ALLOW_UNPRIVILEGED_CREATE = 0x2u

/** SYMBOLIC_LINK_FLAG_DIRECTORY: a link to a directory, the kind `mklink /D` creates. */
private const val DIRECTORY = 0x1u

@OptIn(ExperimentalForeignApi::class)
actual fun createSymbolicLink(link: Path, target: String, directory: Boolean): Boolean {
    val flags = if (directory) ALLOW_UNPRIVILEGED_CREATE or DIRECTORY else ALLOW_UNPRIVILEGED_CREATE
    // Windows resolves a symlink's target with backslashes only.
    if (CreateSymbolicLinkW(link.toString(), target.replace('/', '\\'), flags).toInt() != 0) return true
    check(getenv("CI") == null) { "Can't create symlink '$link' on CI: enable Developer Mode or run as administrator" }
    return false
}
