package io.github.hospes.plexify.core

import kotlinx.io.files.Path

/**
 * Creates a symbolic link at [link] pointing at [target], written as given: a relative [target] (with `/`
 * separators) is relative to [link]'s folder. Returns false if this system doesn't allow creating symlinks (Windows
 * without Developer Mode or administrator rights), so a test can skip instead of failing.
 */
expect fun createSymbolicLink(link: Path, target: String): Boolean
