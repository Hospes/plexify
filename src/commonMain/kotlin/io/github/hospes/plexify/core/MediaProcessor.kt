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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MediaProcessor(
    private val metadataProviders: List<MetadataProvider>,
    private val fileOrganizer: FileOrganizer,
) {
    private val SUPPORTED_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "wmv", "m4v", "mpg", "mpeg", "flv")
    private val MINIMUM_CONFIDENCE_SCORE = 5.0 // A score below this is considered a poor match.

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
        val parsedInfo = MediaFilenameParser.parse(source.name)

        when (parsedInfo) {
            is ParsedMediaInfo.Movie -> processMovie(source, destination, mode, parsedInfo)
            is ParsedMediaInfo.Episode -> processEpisode(source, destination, mode, parsedInfo)
        }
    }


    private suspend fun processMovie(source: Path, destination: Path, mode: OperationMode, parsedInfo: ParsedMediaInfo.Movie) {
        println("  -> Parsed as Movie: Title='${parsedInfo.title}', Year='${parsedInfo.year}'")

        val searchResults = searchProviders(parsedInfo.title, parsedInfo.year)
            .filterIsInstance<MediaSearchResult.Movie>()

        if (searchResults.isEmpty()) {
            println("  -> No metadata found.")
            return
        }

        val canonicalMovie = findAndConsolidateBestMatch(searchResults, parsedInfo.title, parsedInfo.year)
                as? CanonicalMedia.Movie

        if (canonicalMovie == null) {
            println("  -> Could not find a confident match.")
            return
        }

        println("  -> Found match: $canonicalMovie")
        organizeFile(source, destination, canonicalMovie, parsedInfo, mode)
    }

    private suspend fun processEpisode(source: Path, destination: Path, mode: OperationMode, parsedInfo: ParsedMediaInfo.Episode) {
        println("  -> Parsed as TV Show: Show='${parsedInfo.showTitle}', Season: ${parsedInfo.season}, Episode: ${parsedInfo.episode}")

        val searchResults = searchProviders(parsedInfo.showTitle, parsedInfo.year)
            .filterIsInstance<MediaSearchResult.TvShow>()

        if (searchResults.isEmpty()) {
            println("  -> No metadata found for TV show.")
            return
        }

        val canonicalShow = findAndConsolidateBestMatch(searchResults, parsedInfo.showTitle, parsedInfo.year)
                as? CanonicalMedia.TvShow

        if (canonicalShow == null) {
            println("  -> Could not find a confident match for the TV show.")
            return
        }

        println("  -> Found show: $canonicalShow")

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
            async {
                provider.search(title, year)
                    .onSuccess { results -> println("      -> Found ${results.size} results from ${provider::class.simpleName}") }
                    .onFailure { error -> println("      -> Error(${provider::class.simpleName}): ${error.message}") }
            }
        }.awaitAll().flatMap { it.getOrDefault(emptyList()) }
    }

    private suspend fun episodeProviders(show: CanonicalMedia.TvShow, season: Int, episode: Int): List<CanonicalMedia.Episode> = coroutineScope {
        metadataProviders.map { provider ->
            async {
                provider.episode(show, season, episode)
                    .onFailure { error -> println("      -> Error(${provider::class.simpleName}): ${error.message}") }
            }
        }.awaitAll().mapNotNull { it.getOrNull() }
    }

    private fun organizeFile(source: Path, destination: Path, media: CanonicalMedia, parsedInfo: ParsedMediaInfo, mode: OperationMode) {
        fileOrganizer.organize(source, destination, media, parsedInfo, mode)
            .onSuccess { newPath -> println("  -> Successfully organized at: $newPath") }
            .onFailure { error -> println("  -> Error: ${error.message}"); error.printStackTrace() }
    }


    private fun findAndConsolidateBestMatch(
        results: List<MediaSearchResult>,
        parsedTitle: String,
        parsedYear: String?
    ): CanonicalMedia? {
        if (results.isEmpty()) return null

        println("  -> Consolidating ${results.size} results for title: '$parsedTitle' year: '$parsedYear'")

        val groupedByMedia = results.groupBy {
            val normalizedTitle = it.title.lowercase().replace(Regex("[^a-z0-9]"), "")
            "$normalizedTitle:${it.year}"
        }
        println("  -> ${groupedByMedia.size} unique media candidates found.")

        val scoredGroups = groupedByMedia.values.mapNotNull { group ->
            val representative = group.first()
            val groupTitle = representative.title.lowercase()
            var score = 0.0

            val distance = levenshtein(parsedTitle.lowercase(), groupTitle)
            val titleLength = max(parsedTitle.length, groupTitle.length)
            val similarity = if (titleLength > 0) 1.0 - (distance.toDouble() / titleLength) else 0.0

            if (similarity < 0.4) {
                println("    -> Candidate: '${representative.title} (${representative.year})' | Discarded (title similarity too low)")
                return@mapNotNull null
            }

            score += similarity * 10.0
            if (parsedYear != null && representative.year == parsedYear) score += 5.0
            score += (group.distinctBy { it.provider }.size - 1) * 2.0

            // Bonus based on the average confidence score reported by the providers
            val avgProviderConfidence = group.map { it.matchConfidence }.average()
            // We scale it (e.g., divide by 20) to make it a bonus, not the main driver of the score
            score += avgProviderConfidence / 20.0

            println("    -> Candidate: '${representative.title} (${representative.year})' | Score: ${score.format(2)}")
            group to score
        }

        val bestGroup = scoredGroups.maxByOrNull { it.second }
        if (bestGroup == null || bestGroup.second < MINIMUM_CONFIDENCE_SCORE) {
            println("  -> No candidate passed the minimum confidence score of $MINIMUM_CONFIDENCE_SCORE.")
            return null
        }

        println("  -> Best match selected: '${bestGroup.first.first().title}' with score ${bestGroup.second.format(2)}")

        val groupItems = bestGroup.first
        val bestItem = groupItems.firstOrNull { it.year == parsedYear } ?: groupItems.first()

        // Consolidate all IDs from the winning group
        val imdbId = groupItems.firstNotNullOfOrNull { it.imdbId }
        val tmdbId = groupItems.firstNotNullOfOrNull { it.tmdbId }
        val tvdbId = groupItems.firstNotNullOfOrNull { it.tvdbId }

        return when (bestItem) {
            is MediaSearchResult.Movie -> CanonicalMedia.Movie(
                title = bestItem.title,
                year = bestItem.year?.toIntOrNull() ?: 0,
                imdbId = imdbId,
                tmdbId = tmdbId,
                tvdbId = tvdbId
            )

            is MediaSearchResult.TvShow -> CanonicalMedia.TvShow(
                title = bestItem.title,
                year = bestItem.year?.toIntOrNull() ?: 0,
                imdbId = imdbId,
                tmdbId = tmdbId,
                tvdbId = tvdbId
            )
        }
    }
}

// Helper function to format a Double to a specific number of decimal places in a multiplatform-safe way.
private fun Double.format(digits: Int): String {
    val factor = 10.0.pow(digits)
    if (this.isNaN() || this.isInfinite()) {
        return this.toString()
    }
    val scaled = (this * factor).roundToInt()
    val intPart = scaled / factor.toInt()
    val fracPart = scaled.mod(factor.toInt())
    return "$intPart.${fracPart.toString().padStart(digits, '0')}"
}

/**
 * Calculates the Levenshtein distance between two strings.
 * This is a measure of the number of single-character edits (insertions, deletions, or substitutions)
 * required to change one word into the other.
 */
internal fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
    val lhsLength = lhs.length
    val rhsLength = rhs.length

    var cost = Array(lhsLength + 1) { it }
    val newCost = Array(lhsLength + 1) { 0 }

    for (i in 1..rhsLength) {
        newCost[0] = i
        for (j in 1..lhsLength) {
            val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
            val costReplace = cost[j - 1] + match
            val costInsert = cost[j] + 1
            val costDelete = newCost[j - 1] + 1
            newCost[j] = min(min(costInsert, costDelete), costReplace)
        }
        cost = newCost.copyOf()
    }
    return cost[lhsLength]
}