package io.github.hospes.plexify

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.hospes.plexify.web.imdb.ImdbApi
import io.github.hospes.plexify.web.tmdb.TmdbApi
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

object App : CliktCommand(name = "Plexify") {

    private val tmdbApiKey: String by option(help = "TMDB API key").default(BuildConfig.TMDB_API_KEY)
    private val tmdbAccessToken: String by option(help = "TMDB Access Token").default(BuildConfig.TMDB_API_READ_ACCESS_TOKEN)
    private val tvdbApiKey: String by option(help = "TVDB API key").default(BuildConfig.TVDB_API_KEY)

    val sources: List<Path> by argument(name = "source")
        .help("The source path for the media to be managed. This can be a path to a single file, a directory, or multiple paths to various files and directories.")
        .convert { Path(it) }.multiple()

    val count: Int by option().int().default(1).help("Number of greetings")
    //val name: String by option().prompt("Your name").help("The person to greet")

    override fun run() {
        val tmdbApi = TmdbApi(tmdbApiKey, tmdbAccessToken)

        for (source in sources) {
            echo("Source: $source | isAbsolute: ${source.isAbsolute}")
            runBlocking {
                echo("Searching for: $source")

                echo("IMDB ==================")
                ImdbApi.search(source.toString()).also { response ->
                    response.items.forEach { title ->
                        echo("Found title: ${title.id} | ${title.title}")
                    }
                }

                echo("TMDB ==================")
                tmdbApi.search(source.toString()).also { response ->
                    response.items.forEach { title ->
                        echo("Found title: ${title.id} | ${title.title}")
                    }
                }
            }
        }
        echo("Count: $count")
    }
}

fun commonMain(args: Array<String>) = App
    //.subcommands(ExtraCommands, AnotherExtraCommands)
    .main(args)