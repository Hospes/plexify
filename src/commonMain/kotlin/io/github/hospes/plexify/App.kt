package io.github.hospes.plexify

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.groups.default
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import io.github.hospes.plexify.core.DefaultFileOrganizer
import io.github.hospes.plexify.core.MediaProcessor
import io.github.hospes.plexify.data.MetadataCache
import io.github.hospes.plexify.data.tmdb.TmdbCredentials
import io.github.hospes.plexify.data.tmdb.TmdbCredentialsRejectedException
import io.github.hospes.plexify.data.tmdb.TmdbProvider
import io.github.hospes.plexify.data.tmdb.TmdbTimeoutException
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.domain.service.PathFormatter
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

object App : CliktCommand(name = "plexify") {

    // Your own TMDB credentials (flag or environment) take precedence; release binaries
    // fall back to a key built in at release time, shared by every user.
    private val tmdbApiKey: String? by option(
        envvar = "TMDB_API_KEY",
        help = "Your TMDB API key (v3). Overrides the built-in key. Env: TMDB_API_KEY",
    )
    private val tmdbAccessToken: String? by option(
        envvar = "TMDB_API_ACCESS_TOKEN",
        help = "Your TMDB API Read Access Token. Overrides the built-in key; preferred over --tmdb-api-key. Env: TMDB_API_ACCESS_TOKEN",
    )
    private val tmdbCredentials: TmdbCredentials? by lazy {
        TmdbCredentials.resolve(
            userApiKey = tmdbApiKey,
            userAccessToken = tmdbAccessToken,
            builtInApiKey = BuildConfig.TMDB_API_KEY,
            builtInAccessToken = BuildConfig.TMDB_API_ACCESS_TOKEN,
        )
    }
    private val tmdbProvider: TmdbProvider? by lazy { tmdbCredentials?.let { TmdbProvider(it) } }

    val sources: List<Path> by argument(name = "source")
        .help("The source path for the media to be managed. This can be a path to a single file, a directory, or multiple paths to various files and directories.")
        .convert { Path(it) }.multiple(required = true)

    val destination: Path by argument(name = "destination")
        .help("The root directory where the organized library will be created.")
        .convert { Path(it.removeSuffix("\"")) }

    // --- Operation Mode Option ---
    val mode: OperationMode by option("-m", "--mode", help = "Operation mode: MOVE or HARDLINK")
        .enum<OperationMode>(ignoreCase = true)
        .default(OperationMode.HARDLINK)

    val overwrite: Boolean by option(
        "-o", "--overwrite",
        help = "Replace a different file already at the target path instead of skipping it. " +
                "A file that already is the target (or a hardlink to it) is never touched."
    ).flag(default = false)

    val testMode: Boolean by option("--test", help = "Perform a dry run without any actual file operations.")
        .flag(default = false)

    val verbose: Boolean by option("--verbose", help = "Show detailed pipeline logs (parsing, cache, providers, match scoring).")
        .flag(default = false)

    val titleOverride: String? by option(
        "-t", "--title",
        help = "Override the title parsed from filenames. Applies to every file in this run; " +
                "use when release names are too cryptic to detect the correct title."
    )

    val seasonOverride: Int? by option(
        "-s", "--season",
        help = "Override the season number parsed from filenames. Applies to every TV episode in this run " +
                "(ignored for movies); use when the season only appears as a bare folder name like '2'."
    ).int()

    val episodeOffset: Int? by option(
        "--episode-offset",
        help = "Add N to every episode number parsed from filenames (negative values subtract). Applies to every TV " +
                "episode in this run; combine with -s for releases split differently from TMDB, e.g. " +
                "'-s 1 --episode-offset 12' files 'Show S2 [01]' as S01E13."
    ).int()

    val yearOverride: Int? by option(
        "-y", "--year",
        help = "Override the release year parsed from filenames. Applies to every file in this run; " +
                "use when metadata search matches the wrong year."
    ).int()

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
        versionOption(
            version = BuildConfig.VERSION,
            names = setOf("-v", "--version"),
            message = { "Plexify version $it\n$TMDB_ATTRIBUTION" },
        )
    }

    override fun helpEpilog(context: Context): String = TMDB_ATTRIBUTION


    override fun run() {
        val providers = listOf(verifiedTmdbProvider())
        val pathFormatter = PathFormatter()
        val fileOrganizer = DefaultFileOrganizer(pathFormatter, template, overwrite)
        val cache = MetadataCache()

        val metadataService = MetadataService(providers, template)
        val processor = MediaProcessor(metadataService, fileOrganizer, cache, titleOverride, seasonOverride, yearOverride?.toString(), episodeOffset)

        echo("Plexify ${BuildConfig.VERSION} | mode: $mode${if (overwrite) " (overwrite)" else ""} | template: ${template.name} | destination: $destination")
        val overrides = listOfNotNull(
            titleOverride?.let { "title='$it'" },
            seasonOverride?.let { "season=$it" },
            episodeOffset?.let { "episode-offset=$it" },
            yearOverride?.let { "year=$it" },
        )
        if (overrides.isNotEmpty()) {
            echo("Overrides: ${overrides.joinToString(", ")}")
        }
        if (testMode) {
            echo("!!! RUNNING IN TEST MODE (DRY RUN) - NO FILES WILL BE MODIFIED !!!")
        }
        if (verbose) {
            echo("Template: $template")
        }
        echo("---")
        for (source in sources) {
            runBlocking {
                with(LoggingContext(verbose = verbose)) {
                    processor.process(source, destination, mode, testMode)
                }
            }
        }
        echo("---")
        val stats = processor.stats
        echo("Done: ${stats.organized} organized, ${stats.skipped} skipped, ${stats.failed} failed.")
    }

    /**
     * TMDB is the only metadata source, so the run fails up front when there are no credentials
     * or TMDB rejects them, with what to do about it, instead of a miss or an HTTP 401 on every
     * file. Other failures (offline, timeouts) are left to the per-file errors, since they say
     * nothing about the credentials; a timeout gets a warning, since every lookup will wait as long.
     */
    private fun verifiedTmdbProvider(): TmdbProvider {
        // No user credentials and nothing built in: a build made without local.properties.
        val provider = tmdbProvider ?: throw CliktError("No TMDB credentials, and this build has no built-in key. $HOW_TO_SET_TMDB_KEY")
        val error = runBlocking { provider.verifyCredentials() }.exceptionOrNull()
        if (error is TmdbCredentialsRejectedException) {
            val message = when (error.source) {
                TmdbCredentials.Source.USER ->
                    "TMDB rejected your credentials (HTTP 401). Check --tmdb-access-token / TMDB_API_ACCESS_TOKEN " +
                            "and --tmdb-api-key / TMDB_API_KEY" +
                            if (hasBuiltInTmdbKey) ", or unset them to use the built-in key." else "."

                TmdbCredentials.Source.BUILT_IN ->
                    "TMDB rejected the built-in API key (HTTP 401); it may have been revoked. $HOW_TO_SET_TMDB_KEY"
            }
            throw CliktError(message)
        }
        if (error is TmdbTimeoutException) echo("Warning: ${error.message}; TMDB may be unreachable.", err = true)
        return provider
    }

    private val hasBuiltInTmdbKey: Boolean
        get() = BuildConfig.TMDB_API_KEY.isNotBlank() || BuildConfig.TMDB_API_ACCESS_TOKEN.isNotBlank()
}

/** Required by the TMDB API terms of use, section 3. */
const val TMDB_ATTRIBUTION = "This product uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB."

private const val HOW_TO_SET_TMDB_KEY =
    "Get a free key at https://www.themoviedb.org/settings/api and set TMDB_API_ACCESS_TOKEN " +
            "(or pass --tmdb-access-token)."

fun commonMain(args: Array<String>) = App
    //.subcommands(ExtraCommands, AnotherExtraCommands)
    .main(args)