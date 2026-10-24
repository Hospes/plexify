package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import kotlinx.io.files.Path

class PathFormatter {

    // Regex to find placeholders like {cleantitle}, {season:2}, etc.
    private val placeholderRegex = Regex("""\{(\w+)(?::(\d+))?}""", RegexOption.IGNORE_CASE)

    // Regex to find an ID tag that holds one placeholder: Jellyfin's [tmdbid-{tmdbid}] or Plex's {imdb-{imdbid}}.
    // A placeholder on its own, like {year}, is not a tag.
    private val idTagRegex = Regex("""\[[^\[\]{}]*\{(\w+)}[^\[\]{}]*]|\{[^{}]*\{(\w+)}[^{}]*}""")

    // Regex to find a lone placeholder in parentheses like ({year}), with the whitespace before it
    private val parenthesizedPlaceholderRegex = Regex("""\s*\(\{(\w+)(?::\d+)?}\)""")


    fun formatMoviePath(
        folderTemplate: String,
        fileTemplate: String,
        media: CanonicalMedia.Movie,
        parsedInfo: ParsedMediaInfo,
        sourceFile: Path
    ): Path {
        val folder = format(folderTemplate, media, parsedInfo, sourceFile)
        val file = format(fileTemplate, media, parsedInfo, sourceFile)
        return path(listOf(folder), file)
    }

    fun formatEpisodePath(
        showFolderTemplate: String,
        seasonFolderTemplate: String,
        episodeFileTemplate: String,
        media: CanonicalMedia.Episode,
        parsedInfo: ParsedMediaInfo,
        sourceFile: Path
    ): Path {
        val showFolder = format(showFolderTemplate, media, parsedInfo, sourceFile)
        val seasonFolder = format(seasonFolderTemplate, media, parsedInfo, sourceFile)
        val episodeFile = format(episodeFileTemplate, media, parsedInfo, sourceFile)
        return path(listOf(showFolder, seasonFolder), episodeFile)
    }

    /** The path of [folders] and [file], leaving out a folder that renders empty. */
    private fun path(folders: List<String>, file: String): Path {
        // E.g. a title with nothing usable in a name, and no year, version or extension to go with it.
        require(file.isNotEmpty()) { "The naming template renders an empty file name" }
        val segments = folders.filter { it.isNotEmpty() } + file
        return Path(segments.first(), *segments.drop(1).toTypedArray())
    }


    /**
     * The file or folder name [template] renders to: the placeholder values ([buildPlaceholderMap]), the template
     * without the ID tags and parenthesized placeholders that have no value, then the segment ([formatSegment]).
     */
    private fun format(
        template: String,
        media: CanonicalMedia,
        parsedInfo: ParsedMediaInfo,
        sourceFile: Path
    ): String {
        val placeholders = buildPlaceholderMap(media, parsedInfo, sourceFile)
        var result = dropEmptyIdTags(template, placeholders)
        result = dropEmptyParentheses(result, placeholders)
        return formatSegment(result, placeholders)
    }

    // Renders one path segment, shortened to MAX_SEGMENT_BYTES if needed.
    // The episode title is cut first, then the title, so the S/E marker, {version} and the extension stay intact.
    // Only when the titles are cut and the name still doesn't fit does {version} lose its release group.
    private fun formatSegment(template: String, placeholders: Map<String, String?>): String {
        val uses = placeholderRegex.findAll(template).groupingBy { it.groupValues[1].lowercase() }.eachCount()
        var segment = fitSegment(template, placeholders, uses)
        // Nothing before the extension (".mkv", left as "mkv" by finishSegment): the name is empty, not "mkv". Only a
        // defense: the built-in templates always put a title there, and cleanTitle() never leaves it empty.
        if (template.endsWith(".{ext}", ignoreCase = true) && segment == placeholders["ext"]) return ""
        val versionWithoutGroup = placeholders[VERSION_WITHOUT_GROUP]
        if (segment.utf8Size() > MAX_SEGMENT_BYTES && "version" in uses && versionWithoutGroup != placeholders["version"]) {
            segment = fitSegment(template, placeholders + ("version" to versionWithoutGroup), uses)
        }
        if (segment.utf8Size() <= MAX_SEGMENT_BYTES) return segment
        // Only the template's literal text and tags are left to cut: cut the end, keeping the extension.
        val extension = placeholders["ext"]?.takeIf { it.isNotEmpty() && segment.endsWith(".$it") }?.let { ".$it" } ?: ""
        return finishSegment(segment.removeSuffix(extension).takeUtf8(MAX_SEGMENT_BYTES - extension.utf8Size())) + extension
    }

    // The segment with the placeholders of SHORTENED_FIRST cut, in that order, until it fits or they are used up.
    private fun fitSegment(template: String, placeholders: Map<String, String?>, uses: Map<String, Int>): String {
        var values = placeholders
        var segment = finishSegment(replacePlaceholders(template, values))
        for (key in SHORTENED_FIRST.filter { it in uses }) {
            while (segment.utf8Size() > MAX_SEGMENT_BYTES) {
                val value = values[key]?.takeIf { it.isNotEmpty() && it != ELLIPSIS } ?: break
                // A placeholder used twice gives up half the excess at each place.
                val excess = (segment.utf8Size() - MAX_SEGMENT_BYTES + uses.getValue(key) - 1) / uses.getValue(key)
                values = values + (key to shorten(value, value.utf8Size() - excess))
                segment = finishSegment(replacePlaceholders(template, values))
            }
        }
        return segment
    }

    // Collapses repeated spaces and trims the ends. Windows can't keep a name that ends in a dot or a space: it drops
    // them from some segments of a path but not others, so the folder created and the path used for the file differ.
    // A name that starts with a dot is hidden on Linux and macOS, and Plex and Jellyfin skip hidden files and folders,
    // so leading dots go too (".hack//SIGN" -> "hack-SIGN", "...And Justice for All" -> "And Justice for All").
    // Both are trimmed on every platform, so the library has the same names on Linux and Windows.
    private fun finishSegment(segment: String): String =
        segment.replace(Regex("""\s{2,}"""), " ").trim().trimEnd('.', ' ').trimStart { it == '.' || it.isWhitespace() }

    /**
     * [value] cut to at most [maxBytes] in UTF-8, at a word boundary if one is close, and marked with an ellipsis. With
     * no room left it is the ellipsis alone, so the separators around it don't dangle ("S01E01 - … - [720p]").
     */
    private fun shorten(value: String, maxBytes: Int): String {
        val budget = maxBytes - ELLIPSIS.utf8Size()
        if (budget <= 0) return ELLIPSIS
        val cut = value.takeUtf8(budget)
        val atWord = if (value.getOrNull(cut.length) == ' ') cut
        else cut.substringBeforeLast(' ', "").takeIf { it.length >= cut.length / 2 } ?: cut
        return atWord.trimEnd { it.isWhitespace() || it in "&,;:-.…" } + ELLIPSIS
    }

    private fun String.utf8Size(): Int = encodeToByteArray().size

    /** The longest prefix of this string that is at most [maxBytes] in UTF-8, never splitting a surrogate pair. */
    private fun String.takeUtf8(maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < length) {
            val char = this[end]
            val pair = char.isHighSurrogate() && getOrNull(end + 1)?.isLowSurrogate() == true
            val size = when {
                pair -> 4
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                else -> 3
            }
            if (bytes + size > maxBytes) break
            bytes += size
            end += if (pair) 2 else 1
        }
        return substring(0, end)
    }

    // The placeholders that have a value for this media and file, by lowercased name.
    private fun buildPlaceholderMap(media: CanonicalMedia, parsedInfo: ParsedMediaInfo, sourceFile: Path): Map<String, String?> {
        // Composite placeholders like {version}. The release group closes it: " - [1080p] [BluRay] [YTS]".
        // A group that repeats another tag is left out, so the name parses back to the same tags.
        val versionTags = listOfNotNull(parsedInfo.resolution, parsedInfo.quality, parsedInfo.hdr, parsedInfo.edition)
        val releaseGroup = parsedInfo.releaseGroup?.let { cleanName(it.replace("[", "").replace("]", "")) }
            ?.takeIf { group -> group.any { it.isLetterOrDigit() } && versionTags.none { it.equals(group, ignoreCase = true) } }
        fun version(tags: List<String>) = if (tags.isNotEmpty()) " - ${tags.joinToString(" ") { "[$it]" }}" else ""

        val commonPlaceholders = mutableMapOf<String, String?>(
            "version" to version(versionTags + listOfNotNull(releaseGroup)),
            VERSION_WITHOUT_GROUP to version(versionTags),
            "ext" to sourceFile.name.substringAfterLast('.', "")
        )

        when (media) {
            is CanonicalMedia.Movie -> {
                commonPlaceholders.putAll(
                    mapOf(
                        "cleantitle" to cleanTitle(media.title, media.tmdbId, media.imdbId),
                        "year" to media.year?.toString(),
                        "imdbid" to media.imdbId,
                        "tmdbid" to media.tmdbId,
                    )
                )
            }

            is CanonicalMedia.Episode -> {
                commonPlaceholders.putAll(
                    mapOf(
                        "cleantitle" to cleanTitle(media.show.title, media.show.tmdbId, media.show.imdbId),
                        "year" to media.show.year?.toString(),
                        "season" to media.season.toString(),
                        "episode" to media.episode.toString(),
                        // Composite like {version}: "-E02" closing "S01E01-E02" for a multi-episode file, else ""
                        "multiepisode" to (media.lastEpisode?.let { "-E${it.toString().padStart(2, '0')}" } ?: ""),
                        "episodetitle" to cleanName(media.title),
                        "imdbid" to media.show.imdbId,
                        "tmdbid" to media.show.tmdbId,
                    )
                )
            }

            is CanonicalMedia.TvShow -> throw IllegalArgumentException("TV show is not suppose to be here.")
        }
        return commonPlaceholders.filterValues { it != null }.mapKeys { it.key.lowercase() }
    }

    // The {cleantitle} of a movie or show. A title with nothing usable in a name ("?", "//", or dots, which
    // finishSegment trims) would leave the folder without a name: with no year or ID tag, the file would land in the
    // library root. Such a title is named by its ID instead ("tmdb-603", else "imdb-tt0133093").
    private fun cleanTitle(title: String, tmdbId: String?, imdbId: String?): String {
        if (!isNamedById(title)) return cleanName(title)
        return tmdbId?.let { "tmdb-$it" } ?: imdbId?.let { "imdb-$it" }
            ?: throw IllegalArgumentException("The title \"$title\" has nothing usable in a file name, and no ID to name it by")
    }

    // Drops an ID tag whose ID is missing: "[tmdbid-{tmdbid}]" or "{imdb-{imdbid}}" without a value goes whole.
    private fun dropEmptyIdTags(template: String, placeholders: Map<String, String?>): String {
        return idTagRegex.replace(template) { matchResult ->
            val key = matchResult.groupValues[1].ifEmpty { matchResult.groupValues[2] }.lowercase()
            if (placeholders.containsKey(key)) matchResult.value else ""
        }
    }

    // Drops a parenthesized placeholder that has no value, so a title without a year
    // becomes "Title [tmdbid-1]" or "Title.mkv" instead of "Title () [tmdbid-1]".
    private fun dropEmptyParentheses(template: String, placeholders: Map<String, String?>): String {
        return parenthesizedPlaceholderRegex.replace(template) { matchResult ->
            if (placeholders.containsKey(matchResult.groupValues[1].lowercase())) matchResult.value else ""
        }
    }

    // Replaces every placeholder with its value, zero-padded to its width ({season:2}).
    private fun replacePlaceholders(template: String, placeholders: Map<String, String?>): String {
        return placeholderRegex.replace(template) { matchResult ->
            val key = matchResult.groupValues[1].lowercase()
            val padding = matchResult.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            val value = placeholders[key] ?: ""
            if (padding != null) value.padStart(padding, '0') else value
        }
    }

    private companion object {
        /**
         * The longest name of a file or folder: 255 bytes on Linux, 255 UTF-16 units on Windows. A name of 255 bytes
         * in UTF-8 is never more than 255 UTF-16 units, so one limit fits both and names are the same everywhere.
         */
        const val MAX_SEGMENT_BYTES = 255

        /** Placeholders cut to fit a segment, in this order. */
        val SHORTENED_FIRST = listOf("episodetitle", "cleantitle")

        const val ELLIPSIS = "…"

        /** {version} without the release group, for a name too long to keep it. Not a placeholder: `{\w+}` can't name it. */
        const val VERSION_WITHOUT_GROUP = "version:nogroup"
    }
}

// Characters that are invalid in file/directory names, grouped by what cleanName() makes of them
private val joiningSlashRegex = Regex("""(?<=\S)[/\\]+(?=\S)""")
private val separatorCharsRegex = Regex("""[:/\\|\u0000-\u001F]""")
private val droppedCharsRegex = Regex("""[<>"?*]""")

// Makes a title usable in a file or folder name without joining its words. Slashes or backslashes
// between two words become one hyphen ("Fate/Zero" -> "Fate-Zero", "Part 1/2" -> "Part 1-2", ".hack//SIGN" ->
// ".hack-SIGN"); any other separator (":", "|", a slash with a space beside it, a control character) becomes
// a space ("Avatar: The Way of Water" -> "Avatar The Way of Water", "Re:ZERO" -> "Re ZERO");
// ? * " < > are dropped ("M*A*S*H" -> "MASH").
private fun cleanName(value: String): String = value
    .replace(joiningSlashRegex, "-")
    .replace(separatorCharsRegex, " ")
    .replace(droppedCharsRegex, "")
    .replace(Regex("""\s{2,}"""), " ")
    .trim()

/**
 * True when a movie or show [title] has nothing usable in a name ("?", "//", or dots, which finishSegment trims), so
 * its folder and files are named by its ID ("tmdb-1721501").
 */
internal fun isNamedById(title: String): Boolean = cleanName(title).none { it != '.' && !it.isWhitespace() }
