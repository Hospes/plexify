package io.github.hospes.plexify.core.corpus

import io.github.hospes.plexify.core.DefaultFileOrganizer
import io.github.hospes.plexify.core.OrganizeOutcome
import io.github.hospes.plexify.core.RecordingOrganizer
import io.github.hospes.plexify.core.runProcessor
import io.github.hospes.plexify.data.tmdb.TmdbResponder
import io.github.hospes.plexify.data.tmdb.capped
import io.github.hospes.plexify.data.tmdb.mockProvider
import io.github.hospes.plexify.data.tmdb.tokenCredentials
import io.github.hospes.plexify.domain.service.PathFormatter
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * With the Plex template, a show matched by an alias takes its IMDb ID from the request that fetched the alias, and is
 * named as one matched by its own title, whose IMDb ID takes an `external_ids` call: tagged with the ID, or untagged
 * when TMDB has none.
 */
class ImdbIdFromAliasLookupTest {

    private fun tmdb(imdb: String) = tmdbFixture {
        tv(1, "The Solitary Gourmet", 2012, original = "孤独のグルメ", imdb = imdb) {
            alias("Kodoku no Gurume", type = "Romaji")
            season(1, 1..12)
        }
        search("kodoku no gurume", "tv/1")
        search("the solitary gourmet", "tv/1")
    }

    /** Where Plex files [name], and the requests the run made. */
    private suspend fun plexRun(answers: TmdbFixture.Answers, name: String): Pair<String?, String> {
        val tmdb = TmdbResponder(answers.bodies, answers.notFound)
        val organizer = RecordingOrganizer(DefaultFileOrganizer(PathFormatter(), NamingStrategy.Plex))
        val run = runProcessor(mockProvider(credentials = tokenCredentials, handler = tmdb.handler), name, namingStrategy = NamingStrategy.Plex, organizer = organizer)
        assertEquals(emptyList(), tmdb.unanswered)
        val path = (run.outcomeOf(name)?.getOrNull() as? OrganizeOutcome.Organized)?.path?.toString()?.replace('\\', '/')?.substringAfter("/library/")
        return path to requestsByEndpoint(tmdb.paths)
    }

    @Test
    fun `a show matched by an alias is named as one matched by its title`() = capped {
        for ((imdb, folder) in listOf("tt2364582" to "The Solitary Gourmet (2012) {imdb-tt2364582}", NO_IMDB_ID to "The Solitary Gourmet (2012)")) {
            val answers = tmdb(imdb)
            val (byAlias, aliasRequests) = plexRun(answers, "Kodoku.no.Gurume.S01E01.1080p.mkv")
            val (byTitle, titleRequests) = plexRun(answers, "The.Solitary.Gourmet.S01E01.1080p.mkv")

            assertEquals("$folder/Season 01/The Solitary Gourmet (2012) - S01E01 - Episode 1 - [1080p].mkv", byAlias, "IMDb ID '$imdb'")
            assertEquals(byTitle, byAlias)
            // The alias came with the record's details, IMDb ID included; the title's match needed its own call.
            assertEquals("1 search/multi, 1 tv/{id}, 1 tv/{id}/season/{id}", aliasRequests)
            assertEquals("1 search/multi, 1 tv/{id}/external_ids, 1 tv/{id}/season/{id}", titleRequests)
        }
    }
}
