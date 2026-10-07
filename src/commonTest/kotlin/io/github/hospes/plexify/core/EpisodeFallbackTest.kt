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
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpisodeFallbackTest {

    // Two same-titled shows and no year in the filenames: the older one ranks first.
    private val searchResults = listOf(
        MediaSearchResult.TvShow(title = "Ghosts", year = "2019", tmdbId = "1", provider = "TMDb", matchConfidence = 100.0),
        MediaSearchResult.TvShow(title = "Ghosts", year = "2021", tmdbId = "2", provider = "TMDb", matchConfidence = 90.0),
    )

    /** Seasons by show ID, each listing its episode numbers; anything else is a 404. IMDb IDs are "tt" + show ID. */
    private class FakeTmdb(
        private val results: List<MediaSearchResult>,
        private val seasons: Map<String, Map<Int, IntRange>>,
        private val failingSeasons: Set<String> = emptySet(),
    ) : MetadataProvider {
        override val id = "tmdb"
        override val supportedIds = setOf("tmdbid", "imdbid", "tvdbid")
        val seasonFetches = mutableListOf<String>()
        val externalIdLookups = mutableListOf<String>()

        override suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>> = Result.success(results)

        override suspend fun season(show: CanonicalMedia.TvShow, season: Int): Result<CanonicalMedia.Season> {
            val key = "${show.tmdbId}:$season"
            seasonFetches += key
            if (key in failingSeasons) return Result.failure(IllegalStateException("TMDB rate limit reached (HTTP 429)"))
            val episodes = seasons[show.tmdbId]?.get(season)
                ?: return Result.failure(MetadataNotFoundException("HTTP 404 fetching season $season"))
            return Result.success(CanonicalMedia.Season(show, season, episodes.map { CanonicalMedia.Episode(show, season, it, "Episode $it") }))
        }

        override suspend fun episodeGroups(show: CanonicalMedia.TvShow): Result<List<CanonicalMedia.EpisodeGroup>> =
            Result.success(emptyList())

        override suspend fun externalIds(media: CanonicalMedia): Result<ExternalIds> {
            val tmdbId = (media as CanonicalMedia.TvShow).tmdbId!!
            externalIdLookups += tmdbId
            return Result.success(ExternalIds(imdbId = "tt$tmdbId"))
        }
    }

    private class RecordingOrganizer : FileOrganizer {
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

    private class Run(val provider: FakeTmdb, val organizer: RecordingOrganizer, val stats: MediaProcessor.Stats)

    /** Processes empty files with these names, from a fresh temporary directory, with a fresh processor. */
    private suspend fun run(
        provider: FakeTmdb,
        vararg fileNames: String,
        namingStrategy: NamingStrategy = NamingStrategy.Jellyfin,
    ): Run {
        val organizer = RecordingOrganizer()
        val processor = MediaProcessor(
            metadataService = MetadataService(listOf(provider), namingStrategy),
            fileOrganizer = organizer,
            cache = MetadataCache(),
        )
        val dir = Path(SystemTemporaryDirectory, "plexify-fallback-test-${Random.nextLong().toULong()}")
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
        return Run(provider, organizer, processor.stats)
    }

    private fun Run.showOf(fileName: String): String? =
        (organizer.organized[fileName] as? CanonicalMedia.Episode)?.show?.tmdbId

    @Test
    fun `falls back to a runner-up show when the best match lacks the season`() = runTest {
        val provider = FakeTmdb(searchResults, seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..8)))

        val run = run(provider, "Ghosts.S04E01.1080p.WEB.mkv", "Ghosts.S04E02.1080p.WEB.mkv")

        assertEquals("2", run.showOf("Ghosts.S04E01.1080p.WEB.mkv"))
        assertEquals("2", run.showOf("Ghosts.S04E02.1080p.WEB.mkv"))
        // Each show's season is fetched once; the second file reuses the season's show and cache.
        assertEquals(listOf("1:4", "2:4"), run.provider.seasonFetches)
    }

    @Test
    fun `keeps the best match when it has the season but not the episode`() = runTest {
        val provider = FakeTmdb(searchResults, seasons = mapOf("1" to mapOf(4 to 1..8), "2" to mapOf(4 to 1..10)))

        val run = run(provider, "Ghosts.S04E10.1080p.WEB.mkv")

        assertTrue(run.organizer.organized.isEmpty())
        assertEquals(1, run.stats.skipped)
        assertEquals(listOf("1:4"), run.provider.seasonFetches)
    }

    @Test
    fun `does not switch shows when the season failed to load`() = runTest {
        val provider = FakeTmdb(
            searchResults,
            seasons = mapOf("1" to mapOf(4 to 1..8), "2" to mapOf(4 to 1..8)),
            failingSeasons = setOf("1:4"),
        )

        val run = run(provider, "Ghosts.S04E01.1080p.WEB.mkv")

        assertTrue(run.organizer.organized.isEmpty())
        assertEquals(listOf("1:4"), run.provider.seasonFetches)
    }

    @Test
    fun `skips the file when no candidate has the episode`() = runTest {
        val provider = FakeTmdb(searchResults, seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..8)))

        val run = run(provider, "Ghosts.S04E12.1080p.WEB.mkv")

        assertTrue(run.organizer.organized.isEmpty())
        assertEquals(1, run.stats.skipped)
    }

    @Test
    fun `one season never splits across runner-up shows`() = runTest {
        val results = searchResults +
                MediaSearchResult.TvShow(title = "Ghosts", year = "2023", tmdbId = "3", provider = "TMDb", matchConfidence = 80.0)
        // Show 2 has only E01-E02 of season 4, show 3 has E01-E10: matched per file, E01 and E05
        // would land in different shows. Whichever file comes first decides the season's show.
        val provider = FakeTmdb(results, seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..2), "3" to mapOf(4 to 1..10)))

        val run = run(provider, "Ghosts.S04E01.1080p.WEB.mkv", "Ghosts.S04E05.1080p.WEB.mkv")

        val shows = run.organizer.organized.values.map { (it as CanonicalMedia.Episode).show.tmdbId }
        assertTrue(shows.isNotEmpty())
        assertEquals(1, shows.distinct().size, "season 4 split across shows $shows")
    }

    @Test
    fun `a multi-episode file gets the whole range and both titles`() = runTest {
        val provider = FakeTmdb(searchResults, seasons = mapOf("1" to mapOf(1 to 1..6)))

        val run = run(provider, "Ghosts.S01E01E02.1080p.WEB.mkv")

        val episode = run.organizer.organized["Ghosts.S01E01E02.1080p.WEB.mkv"] as CanonicalMedia.Episode
        assertEquals(1, episode.episode)
        assertEquals(2, episode.lastEpisode)
        assertEquals("Episode 1 & Episode 2", episode.title)
        // The range costs no extra fetch: both episodes come from the cached season.
        assertEquals(listOf("1:1"), run.provider.seasonFetches)
    }

    @Test
    fun `a range running past the season is filed as its first episode`() = runTest {
        val provider = FakeTmdb(searchResults, seasons = mapOf("1" to mapOf(1 to 1..6)))

        val run = run(provider, "Ghosts.S01E06-E07.1080p.WEB.mkv")

        val episode = run.organizer.organized["Ghosts.S01E06-E07.1080p.WEB.mkv"] as CanonicalMedia.Episode
        assertEquals(6, episode.episode)
        assertEquals(null, episode.lastEpisode)
        assertEquals("Episode 6", episode.title)
    }

    @Test
    fun `a runner-up show picked by the fallback gets its external ids`() = runTest {
        val provider = FakeTmdb(searchResults, seasons = mapOf("1" to mapOf(1 to 1..6), "2" to mapOf(4 to 1..8)))

        // The Plex template uses {imdbid}, so matched shows get their IMDb ID looked up.
        val run = run(provider, "Ghosts.S04E01.1080p.WEB.mkv", "Ghosts.S04E02.1080p.WEB.mkv", namingStrategy = NamingStrategy.Plex)

        val shows = run.organizer.organized.values.map { (it as CanonicalMedia.Episode).show }
        assertEquals(listOf("tt2", "tt2"), shows.map { it.imdbId })
        // Once for the best match when the show is matched, once for the runner-up when it takes the season.
        assertEquals(listOf("1", "2"), run.provider.externalIdLookups)
    }
}
