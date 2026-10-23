package io.github.hospes.plexify.domain.model

sealed interface ParsedMediaInfo {
    val resolution: String?
    val quality: String?
    val hdr: String?
    val releaseGroup: String?
    val edition: String?

    /** The movie or show ID the path names, which picks the match instead of a title search when it holds. */
    val mediaId: MediaId?

    data class Movie(
        val title: String,
        val year: String?,
        override val resolution: String? = null,
        override val quality: String? = null,
        override val hdr: String? = null,
        override val releaseGroup: String? = null,
        override val edition: String? = null,
        override val mediaId: MediaId? = null,
    ) : ParsedMediaInfo

    data class Episode(
        val showTitle: String,
        val season: Int?,
        val episode: Int,
        val year: String?,
        override val resolution: String? = null,
        override val quality: String? = null,
        override val hdr: String? = null,
        override val releaseGroup: String? = null,
        override val edition: String? = null,
        /** The last episode of a multi-episode file (`S01E01-E03` → 3); null for a single episode. */
        val lastEpisode: Int? = null,
        /**
         * [year] closed the show name without brackets ("Doctor.Who.2005.S01E01"), so it may belong to the
         * title instead ("Space.1999.S01E01" is *Space: 1999*).
         */
        val bareShowYear: Boolean = false,
        /**
         * The number closing [showTitle] read as the season ("Devil May Cry 2", "Shuumatsu no Walkure III"), for
         * a filename with no season marker. Only the provider can tell it from a title's own number ("Kaiju
         * No. 8"), so it is a second reading, not the parse.
         */
        val numberedSeason: NumberedSeason? = null,
        override val mediaId: MediaId? = null,
    ) : ParsedMediaInfo

    /** A show title without its closing number, and that number as a season. */
    data class NumberedSeason(val showTitle: String, val season: Int)
}

/**
 * A movie or show ID named in a path: an ID tag (`[tmdbid-8864]`, `{imdb-tt0361140}`), or the ID plexify names a
 * title by when it has nothing usable in a name (`tmdb-1721501 (2026)`). [fromTitle] marks an ID found where the
 * name's title is such an ID: there is no title to check the record by, so the ID holds only when the record's title
 * is one plexify would name so (a release may be titled like that).
 */
data class MediaId(val database: Database, val id: String, val fromTitle: Boolean = false) {
    enum class Database { TMDB, IMDB }

    /** As plexify names a title by it: "tmdb-8864", "imdb-tt0361140". */
    override fun toString(): String = "${database.name.lowercase()}-$id"
}

/**
 * Applies user-provided CLI overrides on top of what was parsed from the filename.
 * Season and episode-offset overrides only make sense for episodes and are ignored for movies.
 * A title or year override drops the ID the path names: the search runs as the user set it.
 */
fun ParsedMediaInfo.withOverrides(
    title: String?,
    season: Int?,
    year: String? = null,
    episodeOffset: Int? = null,
): ParsedMediaInfo = when (this) {
    is ParsedMediaInfo.Movie -> copy(
        title = title ?: this.title,
        year = year ?: this.year,
        mediaId = mediaId.takeIf { title == null && year == null },
    )
    is ParsedMediaInfo.Episode -> copy(
        showTitle = title ?: showTitle,
        season = season ?: this.season,
        episode = episode + (episodeOffset ?: 0),
        lastEpisode = lastEpisode?.plus(episodeOffset ?: 0),
        year = year ?: this.year,
        mediaId = mediaId.takeIf { title == null && year == null },
    )
}