package io.github.hospes.plexify.domain.strategy

/**
 * Defines a strategy for naming media files and directories.
 * This approach encapsulates the specific template strings for different media servers
 * and allows for easy extension (e.g., for TV shows) in the future.
 */
sealed interface NamingStrategy {
    /** A unique, user-friendly name for the strategy. */
    val name: String

    /** The template for the movie's parent folder. */
    val movieFolderTemplate: String

    /** The template for the movie file itself. Should not contain path separators. */
    val movieFileTemplate: String

    // TV Show Templates
    val tvShowFolderTemplate: String
    val seasonFolderTemplate: String
    val episodeFileTemplate: String

    fun requiredMetadataFields(): Set<String> {
        val allTemplates = listOf(movieFolderTemplate, movieFileTemplate, tvShowFolderTemplate, seasonFolderTemplate, episodeFileTemplate)
        val regex = Regex("""\{(\w+)(?::\d+)?}""", RegexOption.IGNORE_CASE)
        return allTemplates.flatMap { template ->
            regex.findAll(template).map { it.groupValues[1].lowercase() }
        }.toSet()
    }


    /**
     * Naming strategy compliant with Plex's recommended format.
     */
    object Plex : NamingStrategy {
        override val name: String = "Plex"
        private const val BASE_NAME = "{CleanTitle} ({year})"
        override val movieFolderTemplate: String = "$BASE_NAME [imdbid-{imdbid}]"
        // No space before {version}: version string includes its own " - " prefix when non-empty, and is ""
        // when empty — a leading space in the template would leave "Title .ext" when version is absent.
        override val movieFileTemplate: String = "$BASE_NAME{version}.{ext}"

        override val tvShowFolderTemplate: String = "$BASE_NAME [imdbid-{imdbid}]"
        override val seasonFolderTemplate: String = "Season {season:2}"
        override val episodeFileTemplate: String = "$BASE_NAME - S{season:2}E{episode:2} - {episodeTitle}{version}.{ext}"

        override fun toString(): String {
            return """
                $name [
                    movieFolderTemplate='$movieFolderTemplate',
                    movieFileTemplate='$movieFileTemplate',
                    tvShowFolderTemplate='$tvShowFolderTemplate',
                    seasonFolderTemplate='$seasonFolderTemplate',
                    episodeFileTemplate='$episodeFileTemplate'
                ]
            """.trimIndent()
        }
    }

    /**
     * Naming strategy compliant with Jellyfin's recommended format.
     * Uses TMDb IDs (our primary metadata provider). Show folders also include TVDb IDs
     * as conditional tags so that either provider can be used for matching.
     */
    object Jellyfin : NamingStrategy {
        override val name: String = "Jellyfin"
        private const val BASE_NAME = "{CleanTitle} ({year})"
        override val movieFolderTemplate: String = "$BASE_NAME [tmdbid-{tmdbid}]"
        override val movieFileTemplate: String = "$BASE_NAME [tmdbid-{tmdbid}]{version}.{ext}"

        // TV show folders include both TMDb and TVDb ID tags as conditional blocks.
        // Whichever IDs are resolved will appear; absent ones are stripped automatically.
        override val tvShowFolderTemplate: String = "$BASE_NAME [tmdbid-{tmdbid}][tvdbid-{tvdbid}]"
        override val seasonFolderTemplate: String = "Season {season:2}"
        override val episodeFileTemplate: String = "$BASE_NAME - S{season:2}E{episode:2} - {episodeTitle}{version}.{ext}"

        override fun toString(): String {
            return """
                $name [
                    movieFolderTemplate='${movieFolderTemplate}',
                    movieFileTemplate='${movieFileTemplate}',
                    tvShowFolderTemplate='${tvShowFolderTemplate}',
                    seasonFolderTemplate='${seasonFolderTemplate}',
                    episodeFileTemplate='${episodeFileTemplate}'
                ]
            """.trimIndent()
        }
    }

    /**
     * A naming strategy that uses a user-provided custom template string.
     */
    data class Custom(
        private val fullTemplate: String,
    ) : NamingStrategy {
        override val name: String = "Custom"
        override val movieFolderTemplate: String = fullTemplate.substringBeforeLast('/', missingDelimiterValue = "")
        override val movieFileTemplate: String = fullTemplate.substringAfterLast('/')

        override val tvShowFolderTemplate: String = movieFolderTemplate
        override val seasonFolderTemplate: String = "Season {season:2}"
        override val episodeFileTemplate: String = "{CleanTitle} ({year}) - S{season:2}E{episode:2} - {episodeTitle}{version}.{ext}"

        override fun toString(): String {
            return """
                $name [
                    movieFolderTemplate='$movieFolderTemplate',
                    movieFileTemplate='$movieFileTemplate',
                    tvShowFolderTemplate='$tvShowFolderTemplate',
                    seasonFolderTemplate='$seasonFolderTemplate',
                    episodeFileTemplate='$episodeFileTemplate'
                ]
            """.trimIndent()
        }
    }
}