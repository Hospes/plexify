package io.github.hospes.plexify.core.corpus

import io.github.hospes.plexify.data.tmdb.TmdbResponder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A hand-written TMDB: movies and shows with only the fields plexify reads, and the results each search returns, in
 * TMDB's order. [bodies] turns them into the JSON of every endpoint plexify calls, for [TmdbResponder]; anything
 * else (a season, record or search not listed) is a 404.
 *
 * Dates are years. Episodes are named "Episode 1" and so on unless a season names them; episode groups and their
 * parts are numbered, not named (plexify doesn't read those names).
 */
internal class TmdbFixture {
    private val records = linkedMapOf<String, Record>()
    private val searches = linkedMapOf<String, List<String>>()

    class Record(val kind: String, val id: Int, val title: String, val year: Int?, val original: String, val imdb: String?) {
        internal val aliases = mutableListOf<Pair<String, Int?>>()
        internal val seasons = mutableMapOf<Int, List<Pair<Int, String>>>()
        internal val groups = mutableListOf<Pair<Int, List<Pair<Int, List<Pair<Int, Int>>>>>>()

        /** An alternative title; with [season], TMDB tags it with that season ("Season 5"), as it does an anime arc's. */
        fun alias(title: String, season: Int? = null) {
            aliases += title to season
        }

        /** Season [number] with these episode numbers. */
        fun season(number: Int, vararg episodes: IntRange, name: (Int) -> String = { "Episode $it" }) {
            seasons[number] = episodes.flatMap { it.toList() }.map { it to name(it) }
        }

        /** An episode group of TMDB's [type] (1 = original air date … 7 = TV), its parts in order. */
        fun episodeGroup(type: Int, build: GroupBuilder.() -> Unit) {
            groups += type to GroupBuilder().apply(build).parts
        }
    }

    class GroupBuilder {
        internal val parts = mutableListOf<Pair<Int, List<Pair<Int, Int>>>>()

        /** Part [order]: these episodes in this order, as season to episode numbers. */
        fun part(order: Int, vararg episodes: Pair<Int, IntRange>) {
            parts += order to episodes.flatMap { (season, numbers) -> numbers.map { season to it } }
        }
    }

    fun movie(id: Int, title: String, year: Int?, original: String = title, imdb: String? = null, build: Record.() -> Unit = {}) =
        add(Record("movie", id, title, year, original, imdb).apply(build))

    fun tv(id: Int, title: String, year: Int?, original: String = title, imdb: String? = null, build: Record.() -> Unit = {}) =
        add(Record("tv", id, title, year, original, imdb).apply(build))

    private fun add(record: Record) {
        check(records.put("${record.kind}/${record.id}", record) == null) { "${record.kind}/${record.id} is listed twice" }
    }

    /** What `search/multi` returns for [query] (as plexify sends it): records as "movie/603" or "tv/1396". */
    fun search(query: String, vararg results: String) {
        searches["/3/search/multi?query=$query"] = results.toList()
    }

    /** What `search/movie` returns for [query] and [year]. */
    fun searchMovies(query: String, year: Int, vararg results: String) {
        searches["/3/search/movie?query=$query&year=$year"] = results.toList()
    }

    fun bodies(): Map<String, String> = buildMap {
        for ((key, results) in searches) {
            val withType = key.startsWith("/3/search/multi")
            put(key, obj("results" to JsonArray(results.map { searchItem(record(it), withType) })))
        }
        for (record in records.values) putRecord(record)
        for ((imdb, found) in records.values.filter { it.imdb != null }.groupBy { it.imdb!! }) {
            put("/3/find/$imdb", obj(
                "movie_results" to JsonArray(found.filter { it.kind == "movie" }.map { searchItem(it, withType = false) }),
                "tv_results" to JsonArray(found.filter { it.kind == "tv" }.map { searchItem(it, withType = false) }),
            ))
        }
    }

    private fun MutableMap<String, String>.putRecord(record: Record) {
        val path = "/3/${record.kind}/${record.id}"
        val id = JsonPrimitive(record.id)
        val imdb = JsonPrimitive(record.imdb)
        val aliases = JsonArray(record.aliases.map { (title, season) ->
            JsonObject(mapOf("title" to JsonPrimitive(title), "type" to JsonPrimitive(season?.let { "Season $it" } ?: "")))
        })
        put("$path/external_ids", obj("imdb_id" to imdb))
        if (record.kind == "movie") {
            put("$path/alternative_titles", obj("id" to id, "titles" to aliases))
            put(path, obj(
                "id" to id, "title" to JsonPrimitive(record.title), "original_title" to JsonPrimitive(record.original),
                "release_date" to date(record), "imdb_id" to imdb, "alternative_titles" to JsonObject(mapOf("titles" to aliases)),
            ))
            return
        }
        put("$path/alternative_titles", obj("id" to id, "results" to aliases))
        put(path, obj(
            "id" to id, "name" to JsonPrimitive(record.title), "original_name" to JsonPrimitive(record.original),
            "first_air_date" to date(record), "external_ids" to JsonObject(mapOf("imdb_id" to imdb)),
            "alternative_titles" to JsonObject(mapOf("results" to aliases)),
        ))
        for ((number, episodes) in record.seasons.entries.sortedBy { it.key }) {
            put("$path/season/$number", obj(
                "id" to JsonPrimitive(0), "name" to JsonPrimitive("Season $number"), "season_number" to JsonPrimitive(number),
                "episodes" to JsonArray(episodes.map { (episode, name) -> episode(name, episode, number) }),
            ))
        }
        val groupIds = record.groups.indices.map { "${record.id}-${it + 1}" }
        put("$path/episode_groups", obj("results" to JsonArray(record.groups.mapIndexed { index, (type, _) ->
            JsonObject(mapOf("id" to JsonPrimitive(groupIds[index]), "name" to JsonPrimitive("Group ${index + 1}"), "type" to JsonPrimitive(type)))
        })))
        record.groups.forEachIndexed { index, (type, parts) ->
            put("/3/tv/episode_group/${groupIds[index]}", obj(
                "id" to JsonPrimitive(groupIds[index]), "name" to JsonPrimitive("Group ${index + 1}"), "type" to JsonPrimitive(type),
                "groups" to JsonArray(parts.map { (order, episodes) ->
                    JsonObject(mapOf(
                        "name" to JsonPrimitive("Part $order"), "order" to JsonPrimitive(order),
                        "episodes" to JsonArray(episodes.mapIndexed { position, (season, episode) ->
                            JsonObject(episode("Episode $episode", episode, season) + ("order" to JsonPrimitive(position)))
                        }),
                    ))
                }),
            ))
        }
    }

    private fun episode(name: String, episode: Int, season: Int) = JsonObject(mapOf(
        "id" to JsonPrimitive(0), "name" to JsonPrimitive(name), "episode_number" to JsonPrimitive(episode), "season_number" to JsonPrimitive(season),
    ))

    private fun record(key: String) = records[key] ?: error("$key is in a search but not listed")

    private fun searchItem(record: Record, withType: Boolean) = JsonObject(buildMap {
        if (withType) put("media_type", JsonPrimitive(record.kind))
        put("id", JsonPrimitive(record.id))
        if (record.kind == "movie") {
            put("title", JsonPrimitive(record.title))
            put("original_title", JsonPrimitive(record.original))
            put("release_date", date(record))
        } else {
            put("name", JsonPrimitive(record.title))
            put("original_name", JsonPrimitive(record.original))
            put("first_air_date", date(record))
        }
    })

    private fun date(record: Record): JsonElement = record.year?.let { JsonPrimitive(it.toString()) } ?: JsonNull

    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(fields.toMap()).toString()
}

internal fun tmdbFixture(build: TmdbFixture.() -> Unit): Map<String, String> = TmdbFixture().apply(build).bodies()
