package io.github.hospes.plexify.core

import kotlinx.io.files.Path

// Expect function for platform-specific implementation
expect fun createHardLink(source: Path, destination: Path)