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


    /**
     * Naming strategy compliant with Plex's recommended format.
     */
    object Plex : NamingStrategy {
        override val name: String = "Plex"
        private const val BASE_NAME = "{CleanTitle} ({year})"
        override val movieFolderTemplate: String = "$BASE_NAME [imdbid-{imdbid}]"
        override val movieFileTemplate: String = "$BASE_NAME {version}.{ext}"

        override val tvShowFolderTemplate: String = movieFolderTemplate
        override val seasonFolderTemplate: String = "Season {season:2}"
        override val episodeFileTemplate: String = "$BASE_NAME - S{season:2}E{episode:2} - {episodeTitle} {version}.{ext}"

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
     * Includes both IMDb and TMDb IDs for maximum compatibility.
     */
    object Jellyfin : NamingStrategy {
        override val name: String = "Jellyfin"
        private const val BASE_NAME = "{CleanTitle} ({year})"
        override val movieFolderTemplate: String = "$BASE_NAME [imdbid-{imdbid}] [tmdbid-{tmdbid}]"
        override val movieFileTemplate: String = "$BASE_NAME {version}.{ext}"

        override val tvShowFolderTemplate: String = movieFolderTemplate
        override val seasonFolderTemplate: String = "Season {season:2}"
        override val episodeFileTemplate: String = "$BASE_NAME - S{season:2}E{episode:2} - {episodeTitle} {version}.{ext}"

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
        override val episodeFileTemplate: String = "{CleanTitle} ({year}) - S{season:2}E{episode:2} - {episodeTitle} {version}.{ext}"

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