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
 * If [source] is a symbolic link, the link is followed and [destination] becomes a hardlink to the file it points at.
 * Both platforms' own calls (`link` on Linux, `CreateHardLinkW` on Windows) would hardlink the symlink itself, which
 * no longer resolves from another folder when it is relative.
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

/**
 * If [path] is a symbolic link, returns the absolute path of the file it finally points at (after every link in the
 * chain). Returns null if [path] is not a symbolic link.
 *
 * @throws kotlinx.io.IOException If [path] can't be examined, or it is a link that can't be resolved (e.g. dangling).
 */
expect fun resolveSymbolicLink(path: Path): Path?

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
 * Each directory's entries are visited in [FileNameOrder], descending into a subdirectory where its name sorts, so
 * the order is the same on every platform and file system. The file system's own order is not: `readdir` on ext4 is
 * hash order, and FAT32/exFAT list entries in the order they were written. Which file comes first decides which of
 * two files with the same target keeps it, and which show a season is placed in.
 *
 * Directories are not yielded, only traversed. A missing [root] yields nothing. A path that can't be examined,
 * or a directory that can't be listed, is passed to [onUnreadable] with the error and skipped, so the caller can
 * report it instead of losing it silently.
 */
fun walkFiles(
    root: Path,
    fileSystem: MediaFileSystem = PlatformFileSystem,
    onUnreadable: (Path, Exception) -> Unit = { _, _ -> },
): Sequence<Path> = sequence {
    val kind = try {
        fileSystem.kind(root)
    } catch (e: Exception) {
        onUnreadable(root, e)
        return@sequence
    }

    when (kind) {
        FileKind.REGULAR_FILE -> yield(root)
        FileKind.DIRECTORY -> {
            val children = try {
                fileSystem.list(root)
            } catch (e: Exception) {
                onUnreadable(root, e)
                return@sequence
            }
            children.sortedWith(compareBy(FileNameOrder) { it.name })
                .forEach { yieldAll(walkFiles(it, fileSystem, onUnreadable)) }
        }
        FileKind.OTHER, null -> Unit
    }
}

/**
 * The order of names in a directory: ignoring case, with runs of digits compared by value (`E2` before `E10`), the way
 * Windows Explorer and most file managers sort. Names that differ only in case or in leading zeros (`E02`, `E2`) are
 * put in plain string order, so no two different names are equal.
 */
internal val FileNameOrder: Comparator<String> = Comparator { a, b ->
    compareNatural(a, b).takeIf { it != 0 } ?: a.compareTo(b)
}

private fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i] in '0'..'9' && b[j] in '0'..'9') {
            val endA = a.digitRunEnd(i)
            val endB = b.digitRunEnd(j)
            val numberA = a.substring(i, endA).trimStart('0')
            val numberB = b.substring(j, endB).trimStart('0')
            // Without leading zeros, a longer number is a larger one; same length compares digit by digit.
            val byValue = compareValuesBy(numberA, numberB, { it.length }, { it })
            if (byValue != 0) return byValue
            i = endA
            j = endB
        } else {
            val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (byChar != 0) return byChar
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

private fun String.digitRunEnd(start: Int): Int {
    var end = start
    while (end < length && this[end] in '0'..'9') end++
    return end
}
