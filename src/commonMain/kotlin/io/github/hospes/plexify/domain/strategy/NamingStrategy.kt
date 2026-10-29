package io.github.hospes.plexify.domain.strategy

/**
 * Defines a strategy for naming media files and directories: one media server's template strings
 * for movies and TV shows. The built-in strategies are the only ones; there are no user-defined templates.
 */
sealed interface NamingStrategy {
    /** A unique, user-friendly name for the strategy. */
    val name: String

    /** The template for the movie's parent folder. */
    val movieFolderTemplate: String

    /** The template for the movie file itself. Every template renders one file or folder name. */
    val movieFileTemplate: String

    // TV Show Templates
    val tvShowFolderTemplate: String
    val seasonFolderTemplate: String
    val episodeFileTemplate: String

    /** The placeholders the templates use, lowercased ("cleantitle", "imdbid", …). Read once per strategy. */
    fun requiredMetadataFields(): Set<String>

    /** True when a template names folders by the IMDb ID (Plex's do), so a matched movie or show needs that lookup. */
    val needsImdbId: Boolean get() = "imdbid" in requiredMetadataFields()


    /**
     * Naming strategy compliant with Plex's recommended format.
     * Tags movie and show folders with the IMDb ID in `{imdb-tt…}` form, the curly-brace form Plex documents for IDs
     * (the `[imdbid-…]` tag of earlier versions isn't one of them). The tag is left out without an IMDb ID.
     */
    object Plex : NamingStrategy {
        override val name: String = "Plex"
        private const val BASE_NAME = "{CleanTitle} ({year})"
        override val movieFolderTemplate: String = "$BASE_NAME {imdb-{imdbid}}"
        // No space before {version}: version string includes its own " - " prefix when non-empty, and is ""
        // when empty — a leading space in the template would leave "Title .ext" when version is absent.
        override val movieFileTemplate: String = "$BASE_NAME{version}.{ext}"

        override val tvShowFolderTemplate: String = "$BASE_NAME {imdb-{imdbid}}"
        override val seasonFolderTemplate: String = "Season {season:2}"
        override val episodeFileTemplate: String = "$BASE_NAME - S{season:2}E{episode:2}{multiEpisode} - {episodeTitle}{version}.{ext}"

        private val placeholders = placeholdersIn(movieFolderTemplate, movieFileTemplate, tvShowFolderTemplate, seasonFolderTemplate, episodeFileTemplate)
        override fun requiredMetadataFields(): Set<String> = placeholders

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
     * Tags movies and shows with the TMDb ID only: TMDB is our metadata provider and Jellyfin's
     * default one, so a TVDb tag adds nothing.
     */
    object Jellyfin : NamingStrategy {
        override val name: String = "Jellyfin"
        private const val BASE_NAME = "{CleanTitle} ({year})"
        override val movieFolderTemplate: String = "$BASE_NAME [tmdbid-{tmdbid}]"
        override val movieFileTemplate: String = "$BASE_NAME [tmdbid-{tmdbid}]{version}.{ext}"

        override val tvShowFolderTemplate: String = "$BASE_NAME [tmdbid-{tmdbid}]"
        override val seasonFolderTemplate: String = "Season {season:2}"
        override val episodeFileTemplate: String = "$BASE_NAME - S{season:2}E{episode:2}{multiEpisode} - {episodeTitle}{version}.{ext}"

        private val placeholders = placeholdersIn(movieFolderTemplate, movieFileTemplate, tvShowFolderTemplate, seasonFolderTemplate, episodeFileTemplate)
        override fun requiredMetadataFields(): Set<String> = placeholders

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
}

// A placeholder like {cleantitle} or {season:2}, its name in group 1
private val templatePlaceholderRegex = Regex("""\{(\w+)(?::\d+)?}""", RegexOption.IGNORE_CASE)

// The placeholders [templates] use, lowercased
private fun placeholdersIn(vararg templates: String): Set<String> =
    templates.flatMap { template -> templatePlaceholderRegex.findAll(template).map { it.groupValues[1].lowercase() } }.toSet()
