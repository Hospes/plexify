package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import kotlinx.io.files.Path

class PathFormatter {

    // Regex to find placeholders like {title}, {year}, etc.
    private val placeholderRegex = Regex("""\{(\w+)(?::(\d+))?}""", RegexOption.IGNORE_CASE)

    // Regex to find conditional blocks like [imdbid-{imdbid}]
    private val conditionalBlockRegex = Regex("""\[([^\[\]]*?\{(\w+)}[^\[\]]*?)]""")

    // Regex to find and remove characters that are invalid in file/directory names
    private val invalidCharsRegex = Regex("""[<>:"/\\|?*]""")


    fun formatMoviePath(
        folderTemplate: String,
        fileTemplate: String,
        media: CanonicalMedia.Movie,
        parsedInfo: ParsedMediaInfo,
        sourceFile: Path
    ): Path {
        val finalFolder = format(folderTemplate, media, parsedInfo, sourceFile)
        val finalFile = format(fileTemplate, media, parsedInfo, sourceFile)
        return Path(finalFolder, finalFile)
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
        return Path(showFolder, seasonFolder, episodeFile)
    }


    private fun format(
        template: String,
        media: CanonicalMedia,
        parsedInfo: ParsedMediaInfo,
        sourceFile: Path
    ): String {
        val placeholders = buildPlaceholderMap(media, parsedInfo, sourceFile)
        var result = processConditionalBlocks(template, placeholders)
        result = replacePlaceholders(result, placeholders)
        return result.replace(Regex("""\s{2,}"""), " ").trim()
    }

    // 1. Create a map of available placeholders and their values.
    private fun buildPlaceholderMap(media: CanonicalMedia, parsedInfo: ParsedMediaInfo, sourceFile: Path): Map<String, String?> {
        // 1a. Handle special composite placeholders like {version}
        val versionTags = listOfNotNull(parsedInfo.resolution, parsedInfo.edition)
        val versionString = if (versionTags.isNotEmpty()) " - ${versionTags.joinToString(" ") { "[$it]" }}" else ""

        val commonPlaceholders = mutableMapOf(
            "resolution" to parsedInfo.resolution,
            "quality" to parsedInfo.quality,
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
                        "cleantitle" to media.title.replace(invalidCharsRegex, ""),
                        "year" to media.year.toString(),
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
                        "cleantitle" to media.show.title.replace(invalidCharsRegex, ""),
                        "year" to media.show.year.toString(),
                        "season" to media.season.toString(),
                        "episode" to media.episode.toString(),
                        "episodetitle" to media.title.replace(invalidCharsRegex, ""),
                        "imdbid" to media.show.imdbId,
                        "tmdbid" to media.show.tmdbId,
                        "tvdbid" to media.show.tvdbId,
                    )
                )
            }
        }
        return commonPlaceholders.filterValues { it != null }.mapKeys { it.key.lowercase() }
    }

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

    // 3. Replace all remaining placeholders with their actual values.
    private fun replacePlaceholders(template: String, placeholders: Map<String, String?>): String {
        return placeholderRegex.replace(template) { matchResult ->
            val key = matchResult.groupValues[1].lowercase()
            val padding = matchResult.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            val value = placeholders[key] ?: ""
            if (padding != null) value.padStart(padding, '0') else value
        }
    }
}