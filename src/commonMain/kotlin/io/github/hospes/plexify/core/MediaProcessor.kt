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

        val bestMatch = consolidateAndSelectBestMatch(searchResults, parsedInfo.title, parsedInfo.year)
        if (bestMatch.isNullOrEmpty()) {
            println("  -> Could not find a confident match.")
            return
        }

        val canonicalMovie = CanonicalMedia.Movie(
            // Prefer a title from a result that matches the parsed year, otherwise take the first.
            title = bestMatch.firstOrNull { it.year == parsedInfo.year }?.title ?: bestMatch.first().title,
            year = (bestMatch.firstOrNull { it.year == parsedInfo.year }?.year ?: bestMatch.first().year)?.toIntOrNull() ?: 0,
            // Collect all unique IDs from the group.
            imdbId = bestMatch.firstNotNullOfOrNull { it.imdbId },
            tmdbId = bestMatch.firstNotNullOfOrNull { it.tmdbId },
            tvdbId = bestMatch.firstNotNullOfOrNull { it.tvdbId },
        )

        println("  -> Found match: $canonicalMovie")
        organizeFile(source, destination, canonicalMovie, parsedInfo, mode)
    }

    private suspend fun processEpisode(source: Path, destination: Path, mode: OperationMode, parsedInfo: ParsedMediaInfo.Episode) {
        println("  -> Parsed as TV Show: Show='${parsedInfo.showTitle}', Season: ${parsedInfo.season}, Episode: ${parsedInfo.episode}")

        val searchResults = searchProviders(parsedInfo.showTitle, parsedInfo.year).filterIsInstance<MediaSearchResult.TvShow>()
        if (searchResults.isEmpty()) {
            println("  -> No metadata found for TV show.")
            return
        }

        val bestMatch = consolidateAndSelectBestMatch(searchResults, parsedInfo.showTitle, parsedInfo.year)
        if (bestMatch.isNullOrEmpty()) {
            println("  -> Could not find a confident match for the TV show.")
            return
        }

        val canonicalShow = CanonicalMedia.TvShow(
            // Prefer a title from a result that matches the parsed year, otherwise take the first.
            title = bestMatch.firstOrNull { it.year == parsedInfo.year }?.title ?: bestMatch.first().title,
            year = (bestMatch.firstOrNull { it.year == parsedInfo.year }?.year ?: bestMatch.first().year)?.toIntOrNull() ?: 0,
            // Collect all unique IDs from the group.
            imdbId = bestMatch.firstNotNullOfOrNull { it.imdbId },
            tmdbId = bestMatch.firstNotNullOfOrNull { it.tmdbId },
            tvdbId = bestMatch.firstNotNullOfOrNull { it.tvdbId },
        )

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
                    .onSuccess { results ->
                        println("      -> Found ${results.size} results from ${provider::class.simpleName}")
                        results.forEach { println("        -> $it") }
                    }
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

    /**
     * Calculates the Levenshtein distance between two strings.
     * This is a measure of the number of single-character edits (insertions, deletions, or substitutions)
     * required to change one word into the other.
     */
    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
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

    private fun consolidateAndSelectBestMatch(
        results: List<MediaSearchResult>,
        parsedTitle: String,
        parsedYear: String?
    ): List<MediaSearchResult>? {
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
            val groupYear = representative.year

            // 1. Title Similarity Score (most important)
            val distance = levenshtein(parsedTitle.lowercase(), groupTitle)
            val titleLength = max(parsedTitle.length, groupTitle.length)
            val similarity = if (titleLength > 0) 1.0 - (distance.toDouble() / titleLength) else 0.0
            var score = similarity * 10.0 // Score up to 10 points for title match

            // 2. Year Match Bonus
            if (parsedYear != null && groupYear == parsedYear) {
                score += 5
            }

            // 3. Provider Agreement Bonus
            score += (group.distinctBy { it.provider }.size - 1) * 2 // Bonus points if multiple providers agree

            // 4. ID Bonus (less important)
            if (group.any { it.imdbId != null }) {
                score += 1
            }

            println("    -> Candidate: '${representative.title} (${representative.year})' | Score: ${score.format(2)}")

            // Discard matches with very low similarity to avoid completely wrong results
            if (similarity < 0.4) {
                println("       -> Discarded (title similarity too low)")
                null
            } else {
                group to score
            }
        }

        if (scoredGroups.isEmpty()) {
            println("  -> No candidates passed the similarity threshold.")
            return null
        }

        val bestGroup = scoredGroups.maxByOrNull { it.second }

        return bestGroup?.first.also {
            println("  -> Best match selected: '${it?.first()?.title}' with score ${bestGroup?.second?.format(2)}")
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