package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.matching.TitleSeason
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaId

/**
 * The key a show's matches and what the run decided about it are kept under: the title and year it was searched by,
 * or, for a show found by the ID in its path, that ID with the title and year it was ranked for (two shows may share
 * a cleaned title).
 */
internal sealed interface ShowKey {
    data class Searched(val title: String, val year: String?) : ShowKey

    /**
     * [id] as plexify names a title by it ("tmdb-1721501"): one key whichever part of the path the ID was read from.
     * Made from a [MediaId] only, so no other spelling of the ID makes another key.
     */
    @ConsistentCopyVisibility
    data class ById private constructor(val id: String, val title: String, val year: String?) : ShowKey {
        constructor(id: MediaId, title: String, year: String?) : this(id.toString(), title, year)
    }

    /** The key [io.github.hospes.plexify.data.MetadataCache] keeps the show's matches under. */
    val cacheKey: String
        get() = when (this) {
            is Searched -> "$title:$year"
            is ById -> "id:$id:$title:$year"
        }
}

/** A release season of the show [show] names: the season its files are filed in, as named or defaulted. */
internal data class SeasonKey(val show: ShowKey, val season: Int)

/** What the run decided about one [ShowKey], so later files of the show don't decide or report it again. */
internal class ShowState {
    /** The season the title names (an anime arc), once the title is matched; null when it names none. */
    var titleSeason: TitleSeason? = null

    /** The title ends in an arc no season title of the matched show places. */
    var namesUnplacedArc: Boolean = false

    /** Whether the number closing the title is the season; null until decided. */
    var numberIsSeason: Boolean? = null

    /** [titleSeason] has been reported. */
    var titleSeasonReported: Boolean = false

    /** The show each release season was placed in, so later files of the season go to the same show. */
    val seasonShows: MutableMap<Int, CanonicalMedia.TvShow> = mutableMapOf()
}

/** The [ShowState] of every show key of the run. */
internal class ShowStates {
    private val states = mutableMapOf<ShowKey, ShowState>()

    operator fun get(key: ShowKey): ShowState = states.getOrPut(key) { ShowState() }

    /** The show the release season [key] was placed in, if it was yet. */
    fun seasonShow(key: SeasonKey): CanonicalMedia.TvShow? = this[key.show].seasonShows[key.season]

    /** Places the release season [key] in [show], for the rest of its files. */
    fun placeSeason(key: SeasonKey, show: CanonicalMedia.TvShow) {
        this[key.show].seasonShows[key.season] = show
    }
}
