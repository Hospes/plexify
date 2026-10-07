package io.github.hospes.plexify.core

import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path

/**
 * Creates a hard link between two paths.
 *
 * This is an expected platform-specific function that must be implemented for each target.
 * It allows creating a file entry ([destination]) that points to the same underlying data as the [source] file,
 * without duplicating the data on disk.
 *
 * It never replaces an existing file: if [destination] already exists, the call fails.
 *
 * @param source The existing file path to link from.
 * @param destination The new path where the hard link should be created.
 * @throws kotlinx.io.IOException If the link cannot be created (e.g., destination exists, cross-filesystem link,
 *         permissions, or platform limitations). The message is the platform's own error description.
 */
expect fun createHardLink(source: Path, destination: Path)

/**
 * Returns true when [first] and [second] are the same file on disk: the same path, or two hardlinks to the same data
 * (same device and inode on Linux, same volume and file index on Windows). Returns false if either path can't be read.
 */
expect fun isSameFile(first: Path, second: Path): Boolean

/** Appended to the platform error when a hardlink fails because source and destination are on different volumes. */
internal const val CROSS_VOLUME_HINT = " (source and destination must be on the same volume/partition)"

/**
 * Recursively walks the file system from the given [root] path, yielding all regular files found.
 *
 * This extension function traverses directories recursively and returns a [Sequence] of [Path]s
 * representing regular files. Directories themselves are not yielded, only traversed to find files.
 *
 * Usage in contexts like [MediaProcessor] involves iterating this sequence to find and process specific file types
 * (e.g., media files) within a source directory structure.
 *
 * @param root The starting path for the traversal. Can be a file or a directory.
 * @return A [Sequence] of [Path] objects representing all regular files found under [root].
 *         If [root] does not exist, an empty sequence is returned.
 *         Permission errors during directory listing are silently ignored.
 */
fun FileSystem.walk(root: Path): Sequence<Path> = sequence {
    if (!exists(root)) return@sequence
    val metadata = metadataOrNull(root) ?: return@sequence

    if (metadata.isRegularFile) {
        yield(root)
    } else if (metadata.isDirectory) {
        try {
            list(root).forEach { childName ->
                val childPath = Path(root, childName.name)
                yieldAll(walk(childPath))
            }
        } catch (e: Exception) {
            // Handle permission errors silently or log them
        }
    }
}