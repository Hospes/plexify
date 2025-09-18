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

    // Future extension point for TV shows.
    // val tvShowTemplate: String
    // val episodeTemplate: String


    /**
     * Naming strategy compliant with Plex's recommended format.
     */
    object Plex : NamingStrategy {
        override val name: String = "Plex"
        override val movieFolderTemplate: String = "{CleanTitle} ({year}) [imdbid-{imdbid}]"
        override val movieFileTemplate: String = "{CleanTitle} ({year}).{ext}"

        override fun toString(): String {
            return "$name [movieFolderTemplate='${movieFolderTemplate}', movieFileTemplate='${movieFileTemplate}']"
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
        override val movieFileTemplate: String = "$BASE_NAME{version}.{ext}"

        override fun toString(): String {
            return "$name [movieFolderTemplate='${movieFolderTemplate}', movieFileTemplate='${movieFileTemplate}']"
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

        override fun toString(): String {
            return "$name [movieFolderTemplate='${movieFolderTemplate}', movieFileTemplate='${movieFileTemplate}']"
        }
    }
}