package io.github.hospes.plexify

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.groups.default
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.enum
import io.github.hospes.plexify.core.DefaultFileOrganizer
import io.github.hospes.plexify.core.FileOrganizer
import io.github.hospes.plexify.core.MediaProcessor
import io.github.hospes.plexify.data.MetadataCache
import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.data.imdb.ImdbProvider
import io.github.hospes.plexify.data.tmdb.TmdbProvider
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.service.PathFormatter
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

object App : CliktCommand(name = "Plexify") {

    private val tmdbApiKey: String by option(envvar = "TMDB_API_KEY", help = "TMDB API key")
        .default(BuildConfig.TMDB_API_KEY)
    private val tmdbAccessToken: String by option(envvar = "TMDB_API_ACCESS_TOKEN",help = "TMDB Access Token")
        .default(BuildConfig.TMDB_API_ACCESS_TOKEN)
    private val tmdbProvider: MetadataProvider? by lazy { tmdbApiKey.ifBlank { null }?.let { TmdbProvider(it, tmdbAccessToken) } }

    private val tvdbApiKey: String by option(help = "TVDB API key").default(BuildConfig.TVDB_API_KEY)
    private val omdbApiKey: String by option(help = "OMDB API key").default(BuildConfig.OMDB_API_KEY)

    private val imdbProvider: MetadataProvider by lazy { ImdbProvider }


    val sources: List<Path> by argument(name = "source")
        .help("The source path for the media to be managed. This can be a path to a single file, a directory, or multiple paths to various files and directories.")
        .convert { Path(it) }.multiple()

    val destination: Path by argument(name = "destination")
        .help("The root directory where the organized library will be created.")
        .convert { Path(it.removeSuffix("\"")) }

    // --- Operation Mode Option ---
    val mode: OperationMode by option("-m", "--mode", help = "Operation mode: MOVE or HARDLINK")
        .enum<OperationMode>(ignoreCase = true)
        .default(OperationMode.HARDLINK)

    val template: NamingStrategy by mutuallyExclusiveOptions(
        option(
            "-tp", "--template-plex",
            help = "Use a predefined naming template - Plex."
        ).flag().convert { NamingStrategy.Plex },
        option(
            "-tj", "--template-jellyfin",
            help = "Use a predefined naming template - Jellyfin."
        ).flag(default = true).convert { NamingStrategy.Jellyfin },
        option(
            "-tc", "--template-custom",
            help = "Use a custom naming template. Use a forward slash '/' to separate the folder from the filename."
        ).convert { NamingStrategy.Custom(it) },
    ).default(NamingStrategy.Jellyfin)


    init {
        versionOption(version = BuildConfig.VERSION, names = setOf("-v", "--version"))
    }


    override fun run() {
        val providers = listOfNotNull(tmdbProvider, imdbProvider)
        val pathFormatter = PathFormatter()
        val fileOrganizer = DefaultFileOrganizer(pathFormatter, template)
        val cache = MetadataCache()
        val processor = MediaProcessor(providers, fileOrganizer, cache)

        echo("Starting Plexify...")
        echo("Destination: $destination")
        echo("Mode: $mode")
        echo("Template: $template")
        echo("---")
        for (source in sources) {
            runBlocking { processor.process(source, destination, mode) }
        }
        echo("---")
        echo("Done.")
    }
}

private val testFileOrganizer = object : FileOrganizer {
    override fun organize(
        sourceFile: Path,
        destinationRoot: Path,
        media: CanonicalMedia,
        parsedInfo: ParsedMediaInfo,
        mode: OperationMode
    ): Result<Path> = Result.failure(NotImplementedError("Dummy file organizer is not implemented for testing."))
}


fun commonMain(args: Array<String>) = App
    //.subcommands(ExtraCommands, AnotherExtraCommands)
    .main(args)