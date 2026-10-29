package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.tmdb.TMDB_NOT_FOUND
import io.github.hospes.plexify.data.tmdb.capped
import io.github.hospes.plexify.data.tmdb.mockProvider
import io.github.hospes.plexify.data.tmdb.respondJson
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When a search's alternative titles are looked up, through the real provider: only when they could change the match
 * (MatchRanker.aliasesCanChangeWinner), and then the match is the one they give.
 */
class AliasLookupTest {

    // TMDB's answers for "spice and wolf" (October 2026): the 2008 series by its own title, and the 2024 one, which is
    // "Spice and Wolf (2024)" only by an alias.
    private fun spiceAndWolf(aliasLookups: MutableList<String>) = mockProvider { request ->
        when (val path = request.url.encodedPath) {
            "/3/search/multi" -> respondJson(
                """{"results": [
                    {"media_type": "tv", "id": 26707, "name": "Spice and Wolf", "original_name": "狼と香辛料", "first_air_date": "2008-01-09"},
                    {"media_type": "tv", "id": 229676, "name": "Spice and Wolf: MERCHANT MEETS THE WISE WOLF", "original_name": "狼と香辛料 MERCHANT MEETS THE WISE WOLF", "first_air_date": "2024-04-02"}
                ]}""",
            )

            "/3/tv/229676/alternative_titles" -> {
                aliasLookups += path
                respondJson("""{"id": 229676, "results": [{"iso_3166_1": "US", "title": "Spice and Wolf (2024)", "type": ""}]}""")
            }

            else -> {
                if (path.endsWith("/alternative_titles")) aliasLookups += path
                respondJson(TMDB_NOT_FOUND, HttpStatusCode.NotFound)
            }
        }
    }

    @Test
    fun `a remake known by an alias beats an exact title from an earlier year`() = capped {
        val aliasLookups = mutableListOf<String>()

        val run = runProcessor(spiceAndWolf(aliasLookups), "Spice.and.Wolf.2024.S01E02.1080p.WEB-DL.mkv")

        assertEquals(listOf("/3/tv/229676/alternative_titles"), aliasLookups)
        assertTrue(run.output.any { "Matched show:" in it && "[tmdbid-229676]" in it }, run.output.joinToString("\n"))
    }

    @Test
    fun `an exact title no alias can beat looks up none`() = capped {
        val aliasLookups = mutableListOf<String>()

        // With the file from 2008, the first series scores 25, and no alias can save the 2024 one: a show that started
        // 16 years after the file's year contradicts it.
        val run = runProcessor(spiceAndWolf(aliasLookups), "Spice.and.Wolf.2008.S01E02.1080p.BluRay.mkv")

        assertEquals(emptyList(), aliasLookups)
        assertTrue(run.output.any { "Matched show:" in it && "[tmdbid-26707]" in it }, run.output.joinToString("\n"))
    }
}
