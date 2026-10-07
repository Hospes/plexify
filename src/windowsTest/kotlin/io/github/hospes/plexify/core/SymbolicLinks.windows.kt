package io.github.hospes.plexify.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.windows.CreateSymbolicLinkW

/** SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE: works in Developer Mode without administrator rights. */
private const val ALLOW_UNPRIVILEGED_CREATE = 0x2u

@OptIn(ExperimentalForeignApi::class)
actual fun createSymbolicLink(link: Path, target: String): Boolean =
    // Windows resolves a symlink's target with backslashes only.
    CreateSymbolicLinkW(link.toString(), target.replace('/', '\\'), ALLOW_UNPRIVILEGED_CREATE).toInt() != 0
