package io.github.hospes.plexify.core

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

/** What a path points at. */
enum class FileKind { REGULAR_FILE, DIRECTORY, OTHER }

/**
 * File operations on the user's media paths: the source tree and the library. Use [PlatformFileSystem].
 *
 * On Windows, kotlinx-io's [kotlinx.io.files.SystemFileSystem] goes through the ANSI C runtime (`stat`, `opendir`,
 * `mkdir`, `MoveFileExA`), which fails on paths of MAX_PATH (260 characters) or longer. The Windows implementation
 * uses the wide-character Win32 API with `\\?\` paths instead, which has no such limit. On Linux it delegates to
 * kotlinx-io.
 */
interface MediaFileSystem {
    /**
     * Returns what is at [path] (following symlinks), or null if nothing is.
     *
     * @throws kotlinx.io.IOException If [path] can't be examined, e.g. a parent directory isn't readable.
     */
    fun kind(path: Path): FileKind?

    /**
     * Returns the entries of [directory], without `.` and `..`.
     *
     * @throws kotlinx.io.IOException If [directory] can't be listed.
     */
    fun list(directory: Path): List<Path>

    /** Creates [path] and any missing parent directories. Does nothing if [path] already is a directory. */
    fun createDirectories(path: Path)

    /** Renames [source] to [destination] in one step, replacing a file already at [destination]. */
    fun atomicMove(source: Path, destination: Path)

    /** Deletes the file or empty directory at [path]. Does nothing if nothing is there. */
    fun delete(path: Path)
}

expect val PlatformFileSystem: MediaFileSystem

/**
 * Recursively walks the file system from [root], yielding every regular file under it ([root] itself if it is one).
 *
 * Directories are not yielded, only traversed. A missing [root] yields nothing. A path that can't be examined,
 * or a directory that can't be listed, is passed to [onUnreadable] with the error and skipped, so the caller can
 * report it instead of losing it silently.
 */
fun walkFiles(root: Path, onUnreadable: (Path, Exception) -> Unit = { _, _ -> }): Sequence<Path> = sequence {
    val kind = try {
        PlatformFileSystem.kind(root)
    } catch (e: Exception) {
        onUnreadable(root, e)
        return@sequence
    }

    when (kind) {
        FileKind.REGULAR_FILE -> yield(root)
        FileKind.DIRECTORY -> {
            val children = try {
                PlatformFileSystem.list(root)
            } catch (e: Exception) {
                onUnreadable(root, e)
                return@sequence
            }
            children.forEach { yieldAll(walkFiles(it, onUnreadable)) }
        }
        FileKind.OTHER, null -> Unit
    }
}
