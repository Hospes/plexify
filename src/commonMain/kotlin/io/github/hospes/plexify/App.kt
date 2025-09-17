package io.github.hospes.plexify

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.github.hospes.plexify.core.FileOrganizer
import io.github.hospes.plexify.core.MediaProcessor
import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.data.imdb.ImdbProvider
import io.github.hospes.plexify.data.tmdb.TmdbProvider
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.OperationMode
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

object App : CliktCommand(name = "Plexify") {

    private val tmdbApiKey: String by option(help = "TMDB API key").default(BuildConfig.TMDB_API_KEY)
    private val tmdbAccessToken: String by option(help = "TMDB Access Token").default(BuildConfig.TMDB_API_READ_ACCESS_TOKEN)
    private val tmdbProvider: MetadataProvider? by lazy { tmdbApiKey.ifBlank { null }?.let { TmdbProvider(it, tmdbAccessToken) } }

    private val tvdbApiKey: String by option(help = "TVDB API key").default(BuildConfig.TVDB_API_KEY)
    private val omdbApiKey: String by option(help = "OMDB API key").default(BuildConfig.OMDB_API_KEY)

    private val imdbProvider: MetadataProvider by lazy { ImdbProvider }

    val sources: List<Path> by argument(name = "source")
        .help("The source path for the media to be managed. This can be a path to a single file, a directory, or multiple paths to various files and directories.")
        .convert { Path(it) }.multiple()

    override fun run() {
        val providers = listOfNotNull(tmdbProvider, imdbProvider)

        val processor = MediaProcessor(providers, dummyFileOrganizer)

        for (source in sources) {
            echo("Source: $source | isAbsolute: ${source.isAbsolute}")
            runBlocking {
                processor.process(source, Path(""), OperationMode.HARDLINK)
            }
        }
    }
}


private val dummyFileOrganizer = object : FileOrganizer {
    override fun organize(
        sourceFile: Path,
        destinationRoot: Path,
        media: CanonicalMedia,
        mode: OperationMode
    ): Result<Path> = Result.failure(NotImplementedError("Dummy implementation"))
}

fun commonMain(args: Array<String>) = App
    //.subcommands(ExtraCommands, AnotherExtraCommands)
    .main(args)