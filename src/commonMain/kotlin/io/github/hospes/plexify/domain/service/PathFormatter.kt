package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.CanonicalMedia
import kotlinx.io.files.Path

class PathFormatter {

    // Regex to find placeholders like {title}, {year}, etc.
    private val placeholderRegex = Regex("""\{(\w+)}""")

    // Regex to find conditional blocks like [imdbid-{imdbid}]
    private val conditionalBlockRegex = Regex("""\[([^\[\]]*?\{(\w+)}[^\[\]]*?)]""")

    // Regex to find and remove characters that are invalid in file/directory names
    private val invalidCharsRegex = Regex("""[<>:"/\\|?*]""")

    fun format(
        template: String,
        media: CanonicalMedia,
        sourceFile: Path
    ): Path {
        // 1. Create a map of available placeholders and their values.
        val placeholders = mapOf(
            "title" to media.title,
            "cleantitle" to media.title.replace(invalidCharsRegex, ""),
            "year" to media.year.toString(),
            "imdbid" to media.imdbId,
            "tmdbid" to media.tmdbId,
            "ext" to sourceFile.name.substringAfterLast('.', "")
        ).filterValues { it != null } // Filter out null IDs

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
            placeholders[key] ?: "" // Replace with value or empty string if not found
        }

        // 4. Clean up any resulting double slashes or empty segments
        result = result.replace(Regex("""/{2,}"""), "/")
            .removeSuffix("/")

        return Path(result)
    }
}