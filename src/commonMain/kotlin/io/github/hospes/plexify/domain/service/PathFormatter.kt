package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.CanonicalMedia
import kotlinx.io.files.Path

class PathFormatter {

    // Regex to find placeholders like {title}, {year}, etc.
    private val placeholderRegex = Regex("""\{(\w+)}""", RegexOption.IGNORE_CASE)

    // Regex to find conditional blocks like [imdbid-{imdbid}]
    private val conditionalBlockRegex = Regex("""\[([^\[\]]*?\{(\w+)}[^\[\]]*?)]""")

    // Regex to find and remove characters that are invalid in file/directory names
    private val invalidCharsRegex = Regex("""[<>:"/\\|?*]""")

    fun formatPath(
        folderTemplate: String,
        fileTemplate: String,
        media: CanonicalMedia,
        parsedInfo: ParsedMovieInfo,
        sourceFile: Path
    ): Path {
        val finalFolder = format(folderTemplate, media, parsedInfo, sourceFile)
        val finalFile = format(fileTemplate, media, parsedInfo, sourceFile)

        return if (finalFolder.isNotEmpty()) {
            Path(finalFolder, finalFile)
        } else {
            Path(finalFile)
        }
    }

    private fun format(
        template: String,
        media: CanonicalMedia,
        parsedInfo: ParsedMovieInfo,
        sourceFile: Path
    ): String {
        // 1. Create a map of available placeholders and their values.
        val placeholders = mapOf(
            "title" to media.title,
            "cleantitle" to media.title.replace(invalidCharsRegex, ""),
            "year" to media.year.toString(),
            "imdbid" to media.imdbId,
            "tmdbid" to media.tmdbId,
            "resolution" to parsedInfo.resolution,
            "quality" to parsedInfo.quality,
            "releasegroup" to parsedInfo.releaseGroup,
            "edition" to parsedInfo.edition,
            "ext" to sourceFile.name.substringAfterLast('.', "")
        ).filterValues { it != null }.mapKeys { it.key.lowercase() } // Filter out nulls and lowercase keys

        // 1a. Handle special composite placeholders like {version}
        val versionTags = listOfNotNull(parsedInfo.resolution, parsedInfo.edition)
        val versionString = if (versionTags.isNotEmpty()) {
            " - ${versionTags.joinToString(" - ")}"
        } else {
            ""
        }

        // 2. Process conditional blocks first.
        // E.g., for "[imdbid-{imdbid}]", if {imdbid} is not available, the whole block is removed.
        var result = conditionalBlockRegex.replace(template) { matchResult ->
            val placeholderKey = matchResult.groupValues[2]
            if (placeholders.containsKey(placeholderKey)) {
                // If the key exists, return the inner content of the brackets
                matchResult.value
            } else {
                // Otherwise, remove the whole block
                ""
            }
        }

        // 3. Replace all remaining placeholders with their actual values.
        result = placeholderRegex.replace(result) { matchResult ->
            val key = matchResult.groupValues[1].lowercase()
            when (key) {
                "version" -> versionString
                else -> placeholders[key] ?: "" // Replace with value or empty string if not found
            }
        }

        // 4. Clean up any resulting double slashes or empty segments
        return result.replace(Regex("""\s{2,}"""), " ").trim()
    }
}