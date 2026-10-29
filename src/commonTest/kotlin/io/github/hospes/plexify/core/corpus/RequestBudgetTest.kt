package io.github.hospes.plexify.core.corpus

import io.github.hospes.plexify.core.runProcessor
import io.github.hospes.plexify.data.tmdb.TmdbResponder
import io.github.hospes.plexify.data.tmdb.capped
import io.github.hospes.plexify.data.tmdb.mockProvider
import io.github.hospes.plexify.data.tmdb.tokenCredentials
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a few typical runs ask TMDB, by endpoint: corpus names through the real processor and TmdbProvider, against
 * [corpusTmdb]. CorpusTest pins the sums over whole corpora; these pin one scenario each, so a change to what plexify
 * requests (a cache added or lost, a lookup moved) shows up here as a diff with its reason. Each comment says why the
 * count is what it is; a change that is meant to save requests updates the numbers and the comment together.
 */
class RequestBudgetTest {

    // One search for the show, then the episodes find it in the show cache (by title and year). The search looks up
    // the alternative titles of the three results whose titles don't resemble "breaking bad" (El Camino, Breaking Bad
    // Fortune Teller, ISMO – Breaking Bad English). The season is fetched whole for the first episode and cached for
    // the other two.
    @Test
    fun `one season of a show`() = budget(
        sceneNames.filter { it.startsWith("Breaking Bad S01/") },
        "2 movie/{id}/alternative_titles, 1 search/multi, 1 tv/{id}/alternative_titles, 1 tv/{id}/season/{id}",
    )

    // Four release folders of one show, three episodes each (four in Yuukaku-hen): each folder's title is its own
    // search, since shows are cached by the title searched, so the same show is searched four times. Its title
    // ("Demon Slayer: Kimetsu no Yaiba") doesn't resemble any of the four queries, so each search looks up its
    // alternative titles again, which also name the arc's season ("Kimetsu no Yaiba: Yuukaku-hen", "Season 3
    // Romaji"); the plain-title search (the first folder) also looks up those of the two films it returns. One fetch
    // per season (1, 2, 3, 5), cached for the folder's other episodes.
    @Test
    fun `an arc show`() = budget(
        animeNames.filter { it.startsWith("Kimetsu") },
        "2 movie/{id}/alternative_titles, 4 search/multi, 4 tv/{id}/alternative_titles, 4 tv/{id}/season/{id}",
    )

    // TMDB keeps Gate's second cour in Season 1, so Season 2 is a 404 (fetched once, cached as empty) and the episodes
    // are placed through the show's episode groups: the list and its one group are fetched for the first episode and
    // cached for the other two. The search looks up the alternative titles of the three results whose titles don't
    // resemble "gate" (Steins;Gate, Tale Gate, The Ninth Gate).
    @Test
    fun `a cour placed through episode groups`() = budget(
        animeNames.filter { it.startsWith("Gate S2/") },
        "1 movie/{id}/alternative_titles, 1 search/multi, 1 tv/episode_group/{id}, 2 tv/{id}/alternative_titles, " +
            "1 tv/{id}/episode_groups, 1 tv/{id}/season/{id}",
    )

    // The general search doesn't return the 1974 Dracula (TMDB has many), so the movie is searched again with its year.
    // Each search looks up the alternative titles of its first three results whose titles don't resemble "dracula": the
    // general search has more such results, the rest left out (MAX_ALT_TITLES_LOOKUPS); the year search has three.
    @Test
    fun `a movie with a year retry`() = budget(
        listOf("Dracula.1974.720p.BluRay.mkv"),
        "4 movie/{id}/alternative_titles, 1 search/movie, 1 search/multi, 2 tv/{id}/alternative_titles",
    )

    // The second version searches again, alternative-titles lookups and all: movie searches aren't cached (P3-2 will
    // change this). Each search looks up the alternative titles of the first three of the five results that don't
    // resemble "inception" (MAX_ALT_TITLES_LOOKUPS).
    @Test
    fun `a movie in two versions`() = budget(
        listOf("Inception.2010.1080p.BluRay.x264-YTS.mkv", "Inception.2010.1080p.BluRay.x264-SPARKS.mkv"),
        "6 movie/{id}/alternative_titles, 2 search/multi",
    )

    // The two runs above with the Plex template, which names the IMDb ID: the searches and season are the same, plus
    // one external_ids lookup per show and per movie. Those are cached by media, so the show's other episodes and the
    // film's second version don't ask again (though the second version's search does, as above).
    @Test
    fun `a plex run`() = budget(
        sceneNames.filter { it.startsWith("Breaking Bad S01/") } +
            listOf("Inception.2010.1080p.BluRay.x264-YTS.mkv", "Inception.2010.1080p.BluRay.x264-SPARKS.mkv"),
        "8 movie/{id}/alternative_titles, 1 movie/{id}/external_ids, 3 search/multi, 1 tv/{id}/alternative_titles, " +
            "1 tv/{id}/external_ids, 1 tv/{id}/season/{id}",
        NamingStrategy.Plex,
    )

    // What the Plex run writes, run again: no search. An IMDb tag is resolved by find/{imdb}, then the record is
    // fetched with its alternative titles and IMDb ID in the same request (append_to_response), so there is no
    // external_ids lookup. Shows found by ID are cached, so the episodes share one find, one record and one season
    // fetch; movies found by ID aren't, so the second version asks again (P3-2 will change this).
    @Test
    fun `a plex library run again`() = budget(
        listOf(
            "Breaking Bad (2008) {imdb-tt0903747}/Season 01/Breaking Bad (2008) - S01E01 - Episode 1 - [720p] [BluRay].mkv",
            "Breaking Bad (2008) {imdb-tt0903747}/Season 01/Breaking Bad (2008) - S01E02 - Episode 2 - [720p] [BluRay].mkv",
            "Breaking Bad (2008) {imdb-tt0903747}/Season 01/Breaking Bad (2008) - S01E07 - Episode 7 - [720p] [BluRay].mkv",
            "Inception (2010) {imdb-tt1375666}/Inception (2010) - [1080p] [BluRay] [YTS].mkv",
            "Inception (2010) {imdb-tt1375666}/Inception (2010) - [1080p] [BluRay] [SPARKS].mkv",
        ),
        "3 find/{id}, 2 movie/{id}, 1 tv/{id}, 1 tv/{id}/season/{id}",
        NamingStrategy.Plex,
    )

    private fun budget(names: List<String>, expected: String, template: NamingStrategy = NamingStrategy.Jellyfin) = capped {
        val tmdb = TmdbResponder(corpusTmdb.bodies, corpusTmdb.notFound)
        val stats = runProcessor(mockProvider(credentials = tokenCredentials, handler = tmdb.handler), *names.toTypedArray(), namingStrategy = template).stats
        assertEquals(emptyList(), tmdb.unanswered, "Requests CorpusTmdb.kt has no answer for")
        assertEquals("${names.size} organized, 0 skipped, 0 failed", "${stats.organized} organized, ${stats.skipped} skipped, ${stats.failed} failed")
        assertEquals(expected, requestsByEndpoint(tmdb.paths))
    }
}
