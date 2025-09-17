package io.github.hospes.plexify

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.github.hospes.plexify.string.MovieFilenameParser
import io.github.hospes.plexify.web.imdb.ImdbApi
import io.github.hospes.plexify.web.imdb.model.ImdbSearchResponse
import io.github.hospes.plexify.web.omdb.OmdbApi
import io.github.hospes.plexify.web.tmdb.TmdbApi
import io.github.hospes.plexify.web.tmdb.model.TmdbSearchResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

object App : CliktCommand(name = "Plexify") {

    private val tmdbApiKey: String by option(help = "TMDB API key").default(BuildConfig.TMDB_API_KEY)
    private val tmdbAccessToken: String by option(help = "TMDB Access Token").default(BuildConfig.TMDB_API_READ_ACCESS_TOKEN)
    private val tvdbApiKey: String by option(help = "TVDB API key").default(BuildConfig.TVDB_API_KEY)
    private val omdbApiKey: String by option(help = "OMDB API key").default(BuildConfig.OMDB_API_KEY)

    val sources: List<Path> by argument(name = "source")
        .help("The source path for the media to be managed. This can be a path to a single file, a directory, or multiple paths to various files and directories.")
        .convert { Path(it) }.multiple()

    override fun run() {
        val tmdbApi = TmdbApi(tmdbApiKey, tmdbAccessToken)
        val omdbApi = OmdbApi(omdbApiKey)

        for (source in sources) {
            echo("Source: $source | isAbsolute: ${source.isAbsolute}")
            val parsedName = MovieFilenameParser.parse(source.name).title
            runBlocking {
                echo("Searching for: $parsedName | origin: ${source.name}")
                val imdbSearch = async { ImdbApi.search(parsedName) }
                val tmdbSearch = async { tmdbApi.search(parsedName) }

                merger(imdbSearch.await(), tmdbSearch.await())
            }
        }
    }
}

private fun CliktCommand.merger(
    resultImdb: ImdbSearchResponse,
    resultTmdb: TmdbSearchResponse,
) {
    val itemImdb = resultImdb.items.firstOrNull()
    val itemTmdb = resultTmdb.items.firstOrNull()
    echo("Found title: ${itemTmdb?.title ?: itemImdb?.title} | id IMDB: ${itemImdb?.id} | id TMDB: ${itemTmdb?.id}")
}

fun commonMain(args: Array<String>) = App
    //.subcommands(ExtraCommands, AnotherExtraCommands)
    .main(args)