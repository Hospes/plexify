package io.github.hospes.plexify

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.hospes.plexify.web.imdb.ImdbApi
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

object App : CliktCommand(name = "Plexify") {

    val sources: List<Path> by argument(name = "source")
        .help("The source path for the media to be managed. This can be a path to a single file, a directory, or multiple paths to various files and directories.")
        .convert { Path(it) }.multiple()

    val count: Int by option().int().default(1).help("Number of greetings")
    //val name: String by option().prompt("Your name").help("The person to greet")

    override fun run() {
        for (source in sources) {
            echo("Source: $source | isAbsolute: ${source.isAbsolute}")
            runBlocking {
                ImdbApi.search(source.toString()).also { response ->
                    response.titles.forEach { title ->
                        echo("Found title: ${title.id} | ${title.primaryTitle} | ${title.originalTitle}")
                    }
                    //echo("Response: $response")
                }
            }
        }
        echo("Count: $count")
    }
}