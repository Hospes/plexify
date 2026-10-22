package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import kotlinx.io.files.Path

class PathFormatter {

    // Regex to find placeholders like {title}, {year}, etc.
    private val placeholderRegex = Regex("""\{(\w+)(?::(\d+))?}""", RegexOption.IGNORE_CASE)

    // Regex to find conditional blocks like [imdbid-{imdbid}]
    private val conditionalBlockRegex = Regex("""\[([^\[\]]*?\{(\w+)}[^\[\]]*?)]""")

    // Regex to find a lone placeholder in parentheses like ({year}), with the whitespace before it
    private val parenthesizedPlaceholderRegex = Regex("""\s*\(\{(\w+)(?::\d+)?}\)""")

    // Characters that are invalid in file/directory names, grouped by what cleanName() makes of them
    private val joiningSlashRegex = Regex("""(?<=\S)[/\\]+(?=\S)""")
    private val separatorCharsRegex = Regex("""[:/\\|\u0000-\u001F]""")
    private val droppedCharsRegex = Regex("""[<>"?*]""")


    fun formatMoviePath(
        folderTemplate: String,
        fileTemplate: String,
        media: CanonicalMedia.Movie,
        parsedInfo: ParsedMediaInfo,
        sourceFile: Path
    ): Path {
        val folders = format(folderTemplate, media, parsedInfo, sourceFile)
        val file = format(fileTemplate, media, parsedInfo, sourceFile)
        return path(folders, file)
    }

    fun formatEpisodePath(
        showFolderTemplate: String,
        seasonFolderTemplate: String,
        episodeFileTemplate: String,
        media: CanonicalMedia.Episode,
        parsedInfo: ParsedMediaInfo,
        sourceFile: Path
    ): Path {
        val showFolders = format(showFolderTemplate, media, parsedInfo, sourceFile)
        val seasonFolders = format(seasonFolderTemplate, media, parsedInfo, sourceFile)
        val episodeFile = format(episodeFileTemplate, media, parsedInfo, sourceFile)
        return path(showFolders + seasonFolders, episodeFile)
    }

    private fun path(folders: List<String>, file: List<String>): Path {
        // E.g. a title with nothing usable in a name, and no year, version or extension to go with it.
        require(file.isNotEmpty()) { "The naming template renders an empty file name" }
        val segments = folders + file
        return Path(segments.first(), *segments.drop(1).toTypedArray())
    }


    /** The path segments [template] renders to: one per `/`-separated part that isn't empty once rendered. */
    private fun format(
        template: String,
        media: CanonicalMedia,
        parsedInfo: ParsedMediaInfo,
        sourceFile: Path
    ): List<String> {
        val placeholders = buildPlaceholderMap(media, parsedInfo, sourceFile)
        var result = processConditionalBlocks(template, placeholders)
        result = dropEmptyParentheses(result, placeholders)
        return result.split('/').map { formatSegment(it, placeholders) }.filter { it.isNotEmpty() }
    }

    // 4. Render one path segment, shortened to MAX_SEGMENT_BYTES if needed.
    // The episode title is cut first, then the title, so the S/E marker, {version} and the extension stay intact.
    private fun formatSegment(template: String, placeholders: Map<String, String?>): String {
        var values = placeholders
        var segment = finishSegment(replacePlaceholders(template, values))
        val uses = placeholderRegex.findAll(template).groupingBy { it.groupValues[1].lowercase() }.eachCount()
        for (key in SHORTENED_FIRST.filter { it in uses }) {
            while (segment.utf8Size() > MAX_SEGMENT_BYTES) {
                val value = values[key]?.takeIf { it.isNotEmpty() && it != ELLIPSIS } ?: break
                // A placeholder used twice gives up half the excess at each place.
                val excess = (segment.utf8Size() - MAX_SEGMENT_BYTES + uses.getValue(key) - 1) / uses.getValue(key)
                values = values + (key to shorten(value, value.utf8Size() - excess))
                segment = finishSegment(replacePlaceholders(template, values))
            }
        }
        if (segment.utf8Size() <= MAX_SEGMENT_BYTES) return segment
        // Only literal text in a custom template is left to cut: cut the end, keeping the extension.
        val extension = placeholders["ext"]?.takeIf { it.isNotEmpty() && segment.endsWith(".$it") }?.let { ".$it" } ?: ""
        return finishSegment(segment.removeSuffix(extension).takeUtf8(MAX_SEGMENT_BYTES - extension.utf8Size())) + extension
    }

    // 5. Collapse repeated spaces and trim the ends. Windows can't keep a name that ends in a dot or a space: it drops
    // them from some segments of a path but not others, so the folder created and the path used for the file differ.
    // They are trimmed on every platform, so the library has the same names on Linux and Windows.
    private fun finishSegment(segment: String): String =
        segment.replace(Regex("""\s{2,}"""), " ").trim().trimEnd('.', ' ')

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

    // 1. Create a map of available placeholders and their values.
    private fun buildPlaceholderMap(media: CanonicalMedia, parsedInfo: ParsedMediaInfo, sourceFile: Path): Map<String, String?> {
        // 1a. Handle special composite placeholders like {version}
        val versionTags = listOfNotNull(parsedInfo.resolution, parsedInfo.quality, parsedInfo.hdr, parsedInfo.edition)
        val versionString = if (versionTags.isNotEmpty()) " - ${versionTags.joinToString(" ") { "[$it]" }}" else ""

        val commonPlaceholders = mutableMapOf(
            "resolution" to parsedInfo.resolution,
            "quality" to parsedInfo.quality,
            "hdr" to parsedInfo.hdr,
            "releasegroup" to parsedInfo.releaseGroup,
            "edition" to parsedInfo.edition,
            "version" to versionString,
            "ext" to sourceFile.name.substringAfterLast('.', "")
        )

        when (media) {
            is CanonicalMedia.Movie -> {
                commonPlaceholders.putAll(
                    mapOf(
                        "title" to media.title,
                        "cleantitle" to cleanName(media.title),
                        "year" to media.year?.toString(),
                        "imdbid" to media.imdbId,
                        "tmdbid" to media.tmdbId,
                        "tvdbid" to media.tvdbId,
                    )
                )
            }

            is CanonicalMedia.Episode -> {
                commonPlaceholders.putAll(
                    mapOf(
                        "title" to media.show.title,
                        "cleantitle" to cleanName(media.show.title),
                        "year" to media.show.year?.toString(),
                        "season" to media.season.toString(),
                        "episode" to media.episode.toString(),
                        // Composite like {version}: "-E02" closing "S01E01-E02" for a multi-episode file, else ""
                        "multiepisode" to (media.lastEpisode?.let { "-E${it.toString().padStart(2, '0')}" } ?: ""),
                        "episodetitle" to cleanName(media.title),
                        "imdbid" to media.show.imdbId,
                        "tmdbid" to media.show.tmdbId,
                        "tvdbid" to media.show.tvdbId,
                    )
                )
            }

            is CanonicalMedia.TvShow -> throw IllegalArgumentException("TV show is not suppose to be here.")
        }
        return commonPlaceholders.filterValues { it != null }.mapKeys { it.key.lowercase() }
    }

    // 1b. Make a title usable in a file or folder name without joining its words. Slashes or backslashes
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

    // 2. Process conditional blocks first.
    // E.g., for "[imdbid-{imdbid}]", if {imdbid} is not available, the whole block is removed.
    private fun processConditionalBlocks(template: String, placeholders: Map<String, String?>): String {
        return conditionalBlockRegex.replace(template) { matchResult ->
            val placeholderKey = matchResult.groupValues[2].lowercase()
            if (placeholders.containsKey(placeholderKey)) {
                matchResult.value
            } else {
                ""
            }
        }
    }

    // 2b. Drop a parenthesized placeholder that has no value, so a title without a year
    // becomes "Title [tmdbid-1]" or "Title.mkv" instead of "Title () [tmdbid-1]".
    private fun dropEmptyParentheses(template: String, placeholders: Map<String, String?>): String {
        return parenthesizedPlaceholderRegex.replace(template) { matchResult ->
            if (placeholders.containsKey(matchResult.groupValues[1].lowercase())) matchResult.value else ""
        }
    }

    // 3. Replace all remaining placeholders with their actual values.
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
        val SHORTENED_FIRST = listOf("episodetitle", "cleantitle", "title")

        const val ELLIPSIS = "…"
    }
}