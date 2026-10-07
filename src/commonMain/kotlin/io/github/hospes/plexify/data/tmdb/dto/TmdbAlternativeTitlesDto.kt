package io.github.hospes.plexify.data.tmdb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response of `movie/{id}/alternative_titles` and `tv/{id}/alternative_titles`.
 * The movie endpoint returns the list under "titles", the TV endpoint under "results".
 */
@Serializable
data class TmdbAlternativeTitlesDto(
    @SerialName("titles") val titles: List<TmdbAlternativeTitleDto> = emptyList(),
    @SerialName("results") val results: List<TmdbAlternativeTitleDto> = emptyList(),
) {
    val all: List<TmdbAlternativeTitleDto> get() = titles + results

    /**
     * Titles that name one season of a show, by season number. TMDB tags an anime arc's own title
     * with its season in the type ("Kimetsu no Yaiba: Hashira Geiko-hen" is "Season 5 Romaji").
     * A title also listed untagged, or under several seasons, names the whole show and is left out.
     */
    fun seasonTitles(): Map<String, Int> = all
        .groupBy({ it.title }, { it.type?.let(::seasonOfType) })
        .mapNotNull { (title, seasons) -> seasons.distinct().singleOrNull()?.let { title to it } }
        .toMap()
}

// "Season 5", "Season 5 Romaji", "S3"; not "Specials" or "Shortened title".
private val SEASON_TYPE = Regex("""\b(?:season|s)\s*0*(\d{1,3})\b""", RegexOption.IGNORE_CASE)

private fun seasonOfType(type: String): Int? = SEASON_TYPE.find(type)?.groupValues?.get(1)?.toInt()

@Serializable
data class TmdbAlternativeTitleDto(
    @SerialName("iso_3166_1") val country: String? = null,
    @SerialName("title") val title: String,
    @SerialName("type") val type: String? = null,
)
