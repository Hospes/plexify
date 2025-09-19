package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.service.MediaFilenameParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class MediaProcessor(
    private val metadataProviders: List<MetadataProvider>,
    private val fileOrganizer: FileOrganizer,
) {
    private val SUPPORTED_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "wmv", "m4v", "mpg", "mpeg", "flv")


    suspend fun process(source: Path, destination: Path, mode: OperationMode) {
        if (!SystemFileSystem.exists(source)) {
            println("Error: Source path does not exist: $source")
            return
        }

        val metadata = SystemFileSystem.metadataOrNull(source)
        if (metadata == null) {
            println("Can't get metadata for file: $source")
            return
        }

        if (metadata.isDirectory) {
            println("Processing directory: $source")
            val mediaFiles = try {
                SystemFileSystem.list(source)
                    // Construct the full path for each item in the directory
                    .map { child -> Path(source.toString(), child.name) }
                    .filter { fullPath ->
                        val fileMetadata = SystemFileSystem.metadataOrNull(fullPath)
                        fileMetadata?.isRegularFile == true && fullPath.name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
                    }
            } catch (e: Exception) {
                println("  -> Error listing directory contents: ${e.message}")
                return
            }

            if (mediaFiles.isEmpty()) {
                println("  -> No supported media files found.")
                return
            }

            println("  -> Found ${mediaFiles.size} media file(s) to process.")
            mediaFiles.forEachIndexed { index, mediaFile ->
                processFile(mediaFile, destination, mode)
                if (index < mediaFiles.size - 1) {
                    println("---") // Separator for clarity between files
                }
            }
        } else if (metadata.isRegularFile) {
            if (source.name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS) {
                processFile(source, destination, mode)
            } else {
                println("Warning: File is not a supported media type, skipping: $source")
            }
        } else {
            println("Warning: Source is not a regular file or directory, skipping: $source")
        }
    }


    private suspend fun processFile(source: Path, destination: Path, mode: OperationMode) {
        println("Processing: $source")

        // 1. Parse
        val parsedInfo = MediaFilenameParser.parse(source.name)
        //println("  -> Parsed as: $parsedInfo")

        when (parsedInfo) {
            is ParsedMediaInfo.Movie -> processMovie(source, destination, mode, parsedInfo)
            is ParsedMediaInfo.Episode -> processEpisode(source, destination, mode, parsedInfo)
        }
    }


    private suspend fun processMovie(source: Path, destination: Path, mode: OperationMode, parsedInfo: ParsedMediaInfo.Movie) {
        println("  -> Parsed as Movie: Title='${parsedInfo.title}', Year='${parsedInfo.year}'")

        val searchResults = searchProviders(parsedInfo.title, parsedInfo.year).filterIsInstance<MediaSearchResult.Movie>()
        if (searchResults.isEmpty()) {
            println("  -> No metadata found.")
            return
        }

        val bestMatch = consolidateAndSelectBestMatch(searchResults, parsedInfo.year)
        if (bestMatch == null) {
            println("  -> Could not find a confident match.")
            return
        }

        val canonicalMovie = CanonicalMedia.Movie(
            title = bestMatch.title,
            year = bestMatch.year?.toIntOrNull() ?: 0,
            imdbId = bestMatch.imdbId,
            tmdbId = bestMatch.tmdbId
        )

        println("  -> Found match: ${canonicalMovie.title} (${canonicalMovie.year}) [imdbid-${canonicalMovie.imdbId}] [tmdbid-${canonicalMovie.tmdbId}]")
        organizeFile(source, destination, canonicalMovie, parsedInfo, mode)
    }

    private suspend fun processEpisode(source: Path, destination: Path, mode: OperationMode, parsedInfo: ParsedMediaInfo.Episode) {
        println("  -> Parsed as TV Show: Show='${parsedInfo.showTitle}', Season: ${parsedInfo.season}, Episode: ${parsedInfo.episode}")

        val searchResults = searchProviders(parsedInfo.showTitle, parsedInfo.year).filterIsInstance<MediaSearchResult.TvShow>()
        if (searchResults.isEmpty()) {
            println("  -> No metadata found for TV show.")
            return
        }

        val bestShowMatch = consolidateAndSelectBestMatch(searchResults, parsedInfo.year)
        if (bestShowMatch == null) {
            println("  -> Could not find a confident match for the TV show.")
            return
        }

        val canonicalShow = CanonicalMedia.TvShow(
            title = bestShowMatch.title,
            year = bestShowMatch.year?.toIntOrNull() ?: 0,
            imdbId = bestShowMatch.imdbId,
            tmdbId = bestShowMatch.tmdbId
        )

        println("  -> Found show: ${canonicalShow.title} (${canonicalShow.year})")

        // Fetch episode-specific details
        val episodeDetailsResults = episodeProviders(canonicalShow, parsedInfo.season, parsedInfo.episode)
        if (episodeDetailsResults.isEmpty()) {
            println("  -> Episode(S${parsedInfo.season}E${parsedInfo.episode}) not found.")
            return
        }
        val bestEpisodeMatch = episodeDetailsResults.first()

        println("  -> Found episode: S${bestEpisodeMatch.season}E${bestEpisodeMatch.episode} - ${bestEpisodeMatch.title}")
        organizeFile(source, destination, bestEpisodeMatch, parsedInfo, mode)
    }


    private suspend fun searchProviders(title: String, year: String?): List<MediaSearchResult> = coroutineScope {
        metadataProviders.map { provider ->
            async { provider.search(title, year) }
        }.awaitAll().flatMap { it.getOrDefault(emptyList()) }
    }

    private suspend fun episodeProviders(show: CanonicalMedia.TvShow, season: Int, episode: Int): List<CanonicalMedia.Episode> = coroutineScope {
        metadataProviders.map { provider ->
            async { provider.episode(show, season, episode) }
        }.awaitAll().mapNotNull { it.getOrNull() }
    }

    private fun organizeFile(source: Path, destination: Path, media: CanonicalMedia, parsedInfo: ParsedMediaInfo, mode: OperationMode) {
        fileOrganizer.organize(source, destination, media, parsedInfo, mode)
            .onSuccess { newPath -> println("  -> Successfully organized at: $newPath") }
            .onFailure { error -> println("  -> Error: ${error.message}"); error.printStackTrace() }
    }

    private fun consolidateAndSelectBestMatch(
        results: List<MediaSearchResult>,
        parsedYear: String?
    ): MediaSearchResult? {
        if (results.isEmpty()) return null

        val groupedByMedia = results.groupBy {
            val normalizedTitle = it.title.lowercase().replace(Regex("[^a-z0-9]"), "")
            "$normalizedTitle:${it.year}"
        }

        return groupedByMedia.values.maxByOrNull { group ->
            var score = 0
            // Higher score for more providers agreeing on this movie
            score += group.distinctBy { it.provider }.size * 2
            // Higher score if the year matches the one from the filename
            if (parsedYear != null && group.any { it.year == parsedYear }) score += 5
            // Higher score for having an IMDb ID, as it's a great identifier
            if (group.any { it.imdbId != null }) score += 3
            score
        }?.firstOrNull()
    }
}