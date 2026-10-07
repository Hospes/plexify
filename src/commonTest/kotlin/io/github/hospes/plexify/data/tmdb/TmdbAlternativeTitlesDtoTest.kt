package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.tmdb.dto.TmdbAlternativeTitleDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbAlternativeTitlesDto
import kotlin.test.Test
import kotlin.test.assertEquals

class TmdbAlternativeTitlesDtoTest {

    private fun title(title: String, type: String?) = TmdbAlternativeTitleDto(country = "JP", title = title, type = type)

    @Test
    fun `reads the season from the title type`() {
        // Types as seen live on Demon Slayer (tv/85937/alternative_titles).
        val dto = TmdbAlternativeTitlesDto(
            results = listOf(
                title("Kimetsu no Yaiba: Hashira Geiko-hen", "Season 5 Romaji"),
                title("鬼滅の刃 無限列車編", "Season 2"),
                title("鬼灭之刃 游郭篇", "S3"),
                title("Kimetsu no Yaiba", "Romaji"),
                title("Demon Slayer", "Shortened title"),
                title("Kimetsu Academy", "Specials"),
                title("Blade of Demon Destruction", null),
            )
        )

        assertEquals(
            mapOf("Kimetsu no Yaiba: Hashira Geiko-hen" to 5, "鬼滅の刃 無限列車編" to 2, "鬼灭之刃 游郭篇" to 3),
            dto.seasonTitles(),
        )
    }

    @Test
    fun `a title also listed untagged or under two seasons names no season`() {
        val dto = TmdbAlternativeTitlesDto(
            results = listOf(
                title("Demon Slayer: Kimetsu no Yaiba", "Season 1"),
                title("Demon Slayer: Kimetsu no Yaiba", null),
                title("귀멸의 칼날", "S1"),
                title("귀멸의 칼날", "S2"),
                title("귀멸의 칼날 유곽편", "S3"),
                title("귀멸의 칼날 유곽편", "S3"),
            )
        )

        assertEquals(mapOf("귀멸의 칼날 유곽편" to 3), dto.seasonTitles())
    }
}
