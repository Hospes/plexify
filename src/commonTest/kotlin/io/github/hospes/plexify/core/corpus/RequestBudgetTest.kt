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

    // One search for the show, then the episodes find it in the show cache (by title and year). A result is named
    // "Breaking Bad", and no alias could make another one score as high and come first, so no alternative titles are
    // looked up (MatchRanker.aliasesCanChangeWinner). The season is fetched whole for the first episode and cached for
    // the other two.
    @Test
    fun `one season of a show`() = budget(
        sceneNames.filter { it.startsWith("Breaking Bad S01/") },
        "1 search/multi, 1 tv/{id}/season/{id}",
    )

    // Four release folders of one show, three episodes each (four in Yuukaku-hen): each folder's title is its own
    // search, since searches are cached by their exact query, so the same show is searched four times. Its title
    // ("Demon Slayer: Kimetsu no Yaiba") doesn't resemble any of the four queries, and no result is named like them,
    // so each search needs its alternative titles, which also name the arc's season ("Kimetsu no Yaiba: Yuukaku-hen",
    // "Season 3 Romaji"). They are fetched for the first search and come from the cache for the other three; the
    // plain-title search (the first folder) also looks up those of the two films it returns. One fetch per season
    // (1, 2, 3, 5), cached for the folder's other episodes.
    @Test
    fun `an arc show`() = budget(
        animeNames.filter { it.startsWith("Kimetsu") },
        "2 movie/{id}/alternative_titles, 4 search/multi, 1 tv/{id}/alternative_titles, 4 tv/{id}/season/{id}",
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
    // The general search's Draculas are from other years, so it looks up the alternative titles of its first three
    // results whose titles don't resemble "dracula" (MAX_ALT_TITLES_LOOKUPS), in case one is the 1974 film by an alias.
    // The year search finds "Dracula" (1974) itself, and no alias could make another result beat it, so it looks up
    // none.
    @Test
    fun `a movie with a year retry`() = budget(
        listOf("Dracula.1974.720p.BluRay.mkv"),
        "1 movie/{id}/alternative_titles, 1 search/movie, 1 search/multi, 2 tv/{id}/alternative_titles",
    )

    // The run above with a second version of the film: its general search, its alias lookups and its search with the
    // year come from the cache.
    @Test
    fun `a movie with a year retry in two versions`() = budget(
        listOf("Dracula.1974.720p.BluRay.mkv", "Dracula.1974.1080p.BluRay.x264-AMIABLE.mkv"),
        "1 movie/{id}/alternative_titles, 1 search/movie, 1 search/multi, 2 tv/{id}/alternative_titles",
    )

    // Both versions search "inception" (2010): the second one's search comes from the cache. A result is "Inception"
    // (2010), which no alias could beat, so no alternative titles are looked up.
    @Test
    fun `a movie in two versions`() = budget(
        listOf("Inception.2010.1080p.BluRay.x264-YTS.mkv", "Inception.2010.1080p.BluRay.x264-SPARKS.mkv"),
        "1 search/multi",
    )

    // The two runs above with the Plex template, which names the IMDb ID: the searches and season are the same, plus
    // one external_ids lookup per show and per movie. Those are cached by media, so the show's other episodes and the
    // film's second version don't ask again.
    @Test
    fun `a plex run`() = budget(
        sceneNames.filter { it.startsWith("Breaking Bad S01/") } +
            listOf("Inception.2010.1080p.BluRay.x264-YTS.mkv", "Inception.2010.1080p.BluRay.x264-SPARKS.mkv"),
        "1 movie/{id}/external_ids, 2 search/multi, 1 tv/{id}/external_ids, 1 tv/{id}/season/{id}",
        NamingStrategy.Plex,
    )

    // The arc show above with the Plex template: each alias lookup fetches the record's details with its alternative
    // titles appended (and a show's external IDs; a movie's details carry its IMDb ID), the request a lookup by ID
    // sends, so the counts are the same, under other endpoints. The show's IMDb ID came with its aliases, so it takes
    // no external_ids lookup.
    @Test
    fun `an arc show with plex`() = budget(
        animeNames.filter { it.startsWith("Kimetsu") },
        "2 movie/{id}, 4 search/multi, 1 tv/{id}, 4 tv/{id}/season/{id}",
        NamingStrategy.Plex,
    )

    // What the Plex run writes, run again: no search. An IMDb tag is resolved by find/{imdb}, then the record is
    // fetched with its alternative titles and IMDb ID in the same request (append_to_response), so there is no
    // external_ids lookup. Records are cached by the ID that names them, so the episodes share one find, one record
    // and one season fetch, and the film's two versions one find and one record.
    @Test
    fun `a plex library run again`() = budget(
        listOf(
            "Breaking Bad (2008) {imdb-tt0903747}/Season 01/Breaking Bad (2008) - S01E01 - Episode 1 - [720p] [BluRay].mkv",
            "Breaking Bad (2008) {imdb-tt0903747}/Season 01/Breaking Bad (2008) - S01E02 - Episode 2 - [720p] [BluRay].mkv",
            "Breaking Bad (2008) {imdb-tt0903747}/Season 01/Breaking Bad (2008) - S01E07 - Episode 7 - [720p] [BluRay].mkv",
            "Inception (2010) {imdb-tt1375666}/Inception (2010) - [1080p] [BluRay] [YTS].mkv",
            "Inception (2010) {imdb-tt1375666}/Inception (2010) - [1080p] [BluRay] [SPARKS].mkv",
        ),
        "2 find/{id}, 1 movie/{id}, 1 tv/{id}, 1 tv/{id}/season/{id}",
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
