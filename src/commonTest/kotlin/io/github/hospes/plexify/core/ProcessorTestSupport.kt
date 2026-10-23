package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.MetadataCache
import io.github.hospes.plexify.data.MetadataNotFoundException
import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import io.github.hospes.plexify.domain.model.MediaId
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
 * A lookup by ID finds any of these results, or of [idRecords], by its TMDB ID or "tt" + TMDB ID; [idLookupFailure] fails it.
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
    private val idRecords: List<MediaSearchResult> = emptyList(),
    private val idLookupFailure: Throwable? = null,
) : MetadataProvider {
    override val id = "tmdb"
    override val supportedIds = setOf("tmdbid", "imdbid")
    val seasonFetches = mutableListOf<String>()
    val episodeGroupFetches = mutableListOf<String>()
    val externalIdLookups = mutableListOf<String>()
    val yearSearches = mutableListOf<String>()
    val searchedTitles = mutableListOf<String>()
    val idLookups = mutableListOf<String>()

    override suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>> {
        searchedTitles += title
        return Result.success(resultsByTitle[title] ?: results)
    }

    override suspend fun searchMovies(title: String, year: String): Result<List<MediaSearchResult>> {
        yearSearches += year
        yearSearchFailure?.let { return Result.failure(it) }
        return Result.success(moviesByYear[year].orEmpty())
    }

    override suspend fun movie(id: MediaId): Result<MediaSearchResult.Movie> =
        lookUp<MediaSearchResult.Movie>(id).map { it.copy(imdbId = it.imdbIdFor(id), matchConfidence = 0.0) }

    override suspend fun show(id: MediaId): Result<MediaSearchResult.TvShow> =
        lookUp<MediaSearchResult.TvShow>(id).map { it.copy(imdbId = it.imdbIdFor(id), matchConfidence = 0.0) }

    private inline fun <reified T : MediaSearchResult> lookUp(id: MediaId): Result<T> {
        idLookups += id.toString()
        idLookupFailure?.let { return Result.failure(it) }
        val known = results + resultsByTitle.values.flatten() + moviesByYear.values.flatten() + idRecords
        val found = known.filterIsInstance<T>().firstOrNull {
            when (id.database) {
                MediaId.Database.TMDB -> it.tmdbId == id.id
                MediaId.Database.IMDB -> "tt${it.tmdbId}" == id.id
            }
        }
        return found?.let { Result.success(it) } ?: Result.failure(MetadataNotFoundException("HTTP 404 looking up $id"))
    }

    private fun MediaSearchResult.imdbIdFor(id: MediaId) = id.id.takeIf { id.database == MediaId.Database.IMDB } ?: imdbId

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

internal class ProcessorRun<P : MetadataProvider>(val provider: P, val organizer: RecordingOrganizer, val stats: MediaProcessor.Stats) {
    fun showOf(fileName: String): String? = episodeOf(fileName)?.show?.tmdbId

    fun movieOf(fileName: String): String? = (organizer.organized[fileName] as? CanonicalMedia.Movie)?.tmdbId

    fun episodeOf(fileName: String): CanonicalMedia.Episode? = organizer.organized[fileName] as? CanonicalMedia.Episode
}

/**
 * Processes empty files with these names, which may lead through folders ("Show/Season 01/Show - S01E01.mkv"), from
 * a fresh temporary directory (or a folder [dirName] in it), with a fresh processor.
 */
internal suspend fun <P : MetadataProvider> runProcessor(
    provider: P,
    vararg fileNames: String,
    namingStrategy: NamingStrategy = NamingStrategy.Jellyfin,
    seasonOverride: Int? = null,
    yearOverride: String? = null,
    titleOverride: String? = null,
    dirName: String? = null,
): ProcessorRun<P> {
    val organizer = RecordingOrganizer()
    val processor = MediaProcessor(
        metadataService = MetadataService(listOf(provider), namingStrategy),
        fileOrganizer = organizer,
        cache = MetadataCache(),
        titleOverride = titleOverride,
        seasonOverride = seasonOverride,
        yearOverride = yearOverride,
    )
    val root = Path(SystemTemporaryDirectory, "plexify-processor-test-${Random.nextLong().toULong()}")
    val dir = dirName?.let { Path(root, it) } ?: root
    SystemFileSystem.createDirectories(dir)
    val files = fileNames.map { name -> name.split('/').fold(dir) { parent, segment -> Path(parent, segment) } }
    // The folders of names in folders ("Show (2002) [tmdbid-1]/Season 01/Show - S01E01.mkv"), deepest first
    val folders = files.flatMap { file -> generateSequence(file.parent) { it.parent }.takeWhile { it.toString().length > dir.toString().length } }
        .distinctBy { it.toString() }.sortedByDescending { it.toString().length }
    try {
        folders.forEach { SystemFileSystem.createDirectories(it) }
        files.forEach { SystemFileSystem.sink(it).buffered().close() }
        with(LoggingContext()) {
            processor.process(dir, Path(root, "library"), OperationMode.HARDLINK, isTestMode = true)
        }
    } finally {
        files.forEach { SystemFileSystem.delete(it, mustExist = false) }
        folders.forEach { SystemFileSystem.delete(it, mustExist = false) }
        if (dir != root) SystemFileSystem.delete(dir, mustExist = false)
        SystemFileSystem.delete(root, mustExist = false)
    }
    return ProcessorRun(provider, organizer, processor.stats)
}

/** Processes these source paths as given, with a fresh processor and a provider that finds nothing. */
internal suspend fun processSource(vararg sources: Path, isTestMode: Boolean = true): MediaProcessor.Stats {
    val processor = MediaProcessor(
        metadataService = MetadataService(listOf(FakeTmdb(emptyList())), NamingStrategy.Jellyfin),
        fileOrganizer = RecordingOrganizer(),
        cache = MetadataCache(),
    )
    with(LoggingContext()) {
        for (source in sources) processor.process(source, Path(SystemTemporaryDirectory, "plexify-library"), OperationMode.HARDLINK, isTestMode)
    }
    return processor.stats
}
