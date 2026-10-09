package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.MetadataCache
import io.github.hospes.plexify.data.MetadataNotFoundException
import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/**
 * Seasons by show ID, each listing its episode numbers; anything else is a 404. IMDb IDs are "tt" + TMDB ID.
 * Fetching one of [failingSeasons] ("showId:season") fails the first [seasonFailures] times, then loads it.
 * [episodeGroupsOf] gives a show's episode groups; the first [episodeGroupFailures] lookups fail.
 * [moviesByYear] answers the search with a year, empty for any other year; [yearSearchFailure] fails it.
 * [resultsByTitle] answers the general search for those titles instead of [results].
 */
internal class FakeTmdb(
    private val results: List<MediaSearchResult>,
    private val seasons: Map<String, Map<Int, IntRange>> = emptyMap(),
    private val failingSeasons: Set<String> = emptySet(),
    private val seasonFailures: Int = Int.MAX_VALUE,
    private val episodeGroupsOf: (CanonicalMedia.TvShow) -> List<CanonicalMedia.EpisodeGroup> = { emptyList() },
    private val episodeGroupFailures: Int = 0,
    private val moviesByYear: Map<String, List<MediaSearchResult>> = emptyMap(),
    private val yearSearchFailure: Throwable? = null,
    private val resultsByTitle: Map<String, List<MediaSearchResult>> = emptyMap(),
) : MetadataProvider {
    override val id = "tmdb"
    override val supportedIds = setOf("tmdbid", "imdbid", "tvdbid")
    val seasonFetches = mutableListOf<String>()
    val episodeGroupFetches = mutableListOf<String>()
    val externalIdLookups = mutableListOf<String>()
    val yearSearches = mutableListOf<String>()
    val searchedTitles = mutableListOf<String>()

    override suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>> {
        searchedTitles += title
        return Result.success(resultsByTitle[title] ?: results)
    }

    override suspend fun searchMovies(title: String, year: String): Result<List<MediaSearchResult>> {
        yearSearches += year
        yearSearchFailure?.let { return Result.failure(it) }
        return Result.success(moviesByYear[year].orEmpty())
    }

    override suspend fun season(show: CanonicalMedia.TvShow, season: Int): Result<CanonicalMedia.Season> {
        val key = "${show.tmdbId}:$season"
        seasonFetches += key
        if (key in failingSeasons && seasonFetches.count { it == key } <= seasonFailures) return Result.failure(IllegalStateException("TMDB rate limit reached (HTTP 429)"))
        val episodes = seasons[show.tmdbId]?.get(season)
            ?: return Result.failure(MetadataNotFoundException("HTTP 404 fetching season $season"))
        return Result.success(CanonicalMedia.Season(show, season, episodes.map { CanonicalMedia.Episode(show, season, it, "Episode $it") }))
    }

    override suspend fun episodeGroups(show: CanonicalMedia.TvShow): Result<List<CanonicalMedia.EpisodeGroup>> {
        episodeGroupFetches += show.tmdbId!!
        if (episodeGroupFetches.size <= episodeGroupFailures) return Result.failure(IllegalStateException("HTTP 503 fetching episode groups"))
        return Result.success(episodeGroupsOf(show))
    }

    override suspend fun externalIds(media: CanonicalMedia): Result<ExternalIds> {
        val tmdbId = when (media) {
            is CanonicalMedia.Movie -> media.tmdbId
            is CanonicalMedia.TvShow -> media.tmdbId
            else -> null
        }!!
        externalIdLookups += tmdbId
        return Result.success(ExternalIds(imdbId = "tt$tmdbId"))
    }
}

internal class RecordingOrganizer : FileOrganizer {
    val organized = mutableMapOf<String, CanonicalMedia>()

    override fun organize(
        sourceFile: Path,
        destinationRoot: Path,
        media: CanonicalMedia,
        parsedInfo: ParsedMediaInfo,
        mode: OperationMode,
        isTestMode: Boolean,
    ): Result<OrganizeOutcome> {
        organized[sourceFile.name] = media
        return Result.success(OrganizeOutcome.Organized(destinationRoot))
    }
}

internal class ProcessorRun(val provider: FakeTmdb, val organizer: RecordingOrganizer, val stats: MediaProcessor.Stats) {
    fun showOf(fileName: String): String? = episodeOf(fileName)?.show?.tmdbId

    fun movieOf(fileName: String): String? = (organizer.organized[fileName] as? CanonicalMedia.Movie)?.tmdbId

    fun episodeOf(fileName: String): CanonicalMedia.Episode? = organizer.organized[fileName] as? CanonicalMedia.Episode
}

/** Processes empty files with these names, from a fresh temporary directory, with a fresh processor. */
internal suspend fun runProcessor(
    provider: FakeTmdb,
    vararg fileNames: String,
    namingStrategy: NamingStrategy = NamingStrategy.Jellyfin,
    seasonOverride: Int? = null,
    yearOverride: String? = null,
): ProcessorRun {
    val organizer = RecordingOrganizer()
    val processor = MediaProcessor(
        metadataService = MetadataService(listOf(provider), namingStrategy),
        fileOrganizer = organizer,
        cache = MetadataCache(),
        seasonOverride = seasonOverride,
        yearOverride = yearOverride,
    )
    val dir = Path(SystemTemporaryDirectory, "plexify-processor-test-${Random.nextLong().toULong()}")
    SystemFileSystem.createDirectories(dir)
    val files = fileNames.map { Path(dir, it) }
    try {
        files.forEach { SystemFileSystem.sink(it).buffered().close() }
        with(LoggingContext()) {
            processor.process(dir, Path(dir, "library"), OperationMode.HARDLINK, isTestMode = true)
        }
    } finally {
        files.forEach { SystemFileSystem.delete(it, mustExist = false) }
        SystemFileSystem.delete(dir, mustExist = false)
    }
    return ProcessorRun(provider, organizer, processor.stats)
}
