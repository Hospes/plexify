package io.github.hospes.plexify.domain.strategy

/**
 * Defines a strategy for naming media files and directories.
 * This approach encapsulates the specific template strings for different media servers
 * and allows for easy extension (e.g., for TV shows) in the future.
 */
sealed interface NamingStrategy {
    /** A unique, user-friendly name for the strategy. */
    val name: String

    /** The template string used for organizing movies. */
    val movieTemplate: String

    // Future extension point for TV shows.
    // val tvShowTemplate: String
    // val episodeTemplate: String


    /**
     * Naming strategy compliant with Plex's recommended format.
     */
    object Plex : NamingStrategy {
        override val name: String = "Plex"
        override val movieTemplate: String = "{CleanTitle} ({year}) [imdbid-{imdbid}]/{CleanTitle} ({year}).{ext}"
    }

    /**
     * Naming strategy compliant with Jellyfin's recommended format.
     * Includes both IMDb and TMDb IDs for maximum compatibility.
     */
    object Jellyfin : NamingStrategy {
        override val name: String = "Jellyfin"
        override val movieTemplate: String = "{CleanTitle} ({year}) [imdbid-{imdbid}] [tmdbid-{tmdbid}]/{CleanTitle} ({year}).{ext}"
    }

    /**
     * A naming strategy that uses a user-provided custom template string.
     */
    data class Custom(
        override val movieTemplate: String
    ) : NamingStrategy {
        override val name: String = "Custom"
    }
}