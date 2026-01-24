package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.MetadataCache
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.service.MediaFilenameParser
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.logging.LoggingContext
import io.github.hospes.plexify.logging.indent
import io.github.hospes.plexify.logging.log
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MediaProcessor(
    private val metadataService: MetadataService,
    private val fileOrganizer: FileOrganizer,
    private val cache: MetadataCache,
) {
    private val SUPPORTED_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "wmv", "m4v", "mpg", "mpeg", "flv")
    private val MINIMUM_CONFIDENCE_SCORE = 5.0 // A score below this is considered a poor match.

    context(_: LoggingContext)
    suspend fun process(source: Path, destination: Path, mode: OperationMode, isTestMode: Boolean) {
        if (!SystemFileSystem.exists(source)) {
            log("Error: Source path does not exist: $source")
            return
        }

        val metadata = SystemFileSystem.metadataOrNull(source)
        if (metadata == null) {
            log("Can't get metadata for file: $source")
            return
        }

        if (metadata.isDirectory) {
            log("Processing directory: $source")
            val mediaFiles = try {
                SystemFileSystem.walk(source)
                    .filter { fullPath ->
                        val fileMetadata = SystemFileSystem.metadataOrNull(fullPath)
                        fileMetadata?.isRegularFile == true && fullPath.name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
                    }
                    .toList()
            } catch (e: Exception) {
                log("Error listing directory contents: ${e.message}")
                return
            }

            if (mediaFiles.isEmpty()) {
                log("No supported media files found.")
                return
            }

            log("Found ${mediaFiles.size} media file(s) to process.")
            mediaFiles.forEachIndexed { index, mediaFile ->
                processFile(mediaFile, destination, mode, isTestMode)
                if (index < mediaFiles.size - 1) {
                    log("---") // Separator for clarity between files
                }
            }
        } else if (metadata.isRegularFile) {
            if (source.name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS) {
                processFile(source, destination, mode, isTestMode)
            } else {
                log("Warning: File is not a supported media type, skipping: $source")
            }
        } else {
            log("Warning: Source is not a regular file or directory, skipping: $source")
        }
    }

    context(_: LoggingContext)
    private suspend fun processFile(source: Path, destination: Path, mode: OperationMode, isTestMode: Boolean) = indent {
        log("Processing: $source")
        when (val parsedInfo = MediaFilenameParser.parse(source.name)) {
            is ParsedMediaInfo.Movie -> processMovie(source, destination, mode, parsedInfo, isTestMode)
            is ParsedMediaInfo.Episode -> processEpisode(source, destination, mode, parsedInfo, isTestMode)
        }
    }

    context(_: LoggingContext)
    private suspend fun processMovie(
        source: Path,
        destination: Path,
        mode: OperationMode,
        parsedInfo: ParsedMediaInfo.Movie,
        isTestMode: Boolean,
    ) = indent {
        log("Parsed as Movie: Title='${parsedInfo.title}', Year='${parsedInfo.year}'")

        val searchResults = metadataService.search(parsedInfo.title, parsedInfo.year)
            .filterIsInstance<MediaSearchResult.Movie>()

        if (searchResults.isEmpty()) {
            log("No metadata found.")
            return@indent
        }

        val canonicalMovie = findAndConsolidateBestMatch(searchResults, parsedInfo.title, parsedInfo.year)
                as? CanonicalMedia.Movie

        if (canonicalMovie == null) {
            log("Could not find a confident match.")
            return@indent
        }

        log("Found match: $canonicalMovie")
        organizeFile(source, destination, canonicalMovie, parsedInfo, mode, isTestMode)
    }

    context(_: LoggingContext)
    private suspend fun processEpisode(
        source: Path,
        destination: Path,
        mode: OperationMode,
        parsedInfo: ParsedMediaInfo.Episode,
        isTestMode: Boolean,
    ) = indent {
        log("Parsed as TV Show: Show='${parsedInfo.showTitle}', Season: ${parsedInfo.season}, Episode: ${parsedInfo.episode}")

        // Step 1: Find the canonical show, using the cache first.
        val canonicalShow = findOrFetchShow(parsedInfo.showTitle, parsedInfo.year)
        if (canonicalShow == null) {
            log("Could not find a confident match for the TV show.")
            return@indent
        }
        log("Found show: $canonicalShow")

        // Step 2: Find the episode details, using the cache first.
        val bestEpisodeMatch = findOrFetchEpisode(canonicalShow, parsedInfo.season, parsedInfo.episode)
        if (bestEpisodeMatch == null) {
            log("Episode(S${parsedInfo.season}E${parsedInfo.episode}) not found.")
            return@indent
        }

        log("Found episode: S${bestEpisodeMatch.season}E${bestEpisodeMatch.episode} - ${bestEpisodeMatch.title}")
        organizeFile(source, destination, bestEpisodeMatch, parsedInfo, mode, isTestMode)
    }

    /**
     * Helper function to get a TV show's metadata, checking the cache before fetching from providers.
     */
    context(_: LoggingContext)
    private suspend fun findOrFetchShow(title: String, year: String?): CanonicalMedia.TvShow? = indent {
        val cacheKey = "$title:$year"
        val cachedShow = cache.getShow(cacheKey)
        if (cachedShow != null) {
            log("Cache HIT for show: '$title'")
            return@indent cachedShow
        }
        log("Cache MISS for show: '$title'. Searching providers...")

        val searchResults = metadataService.search(title, year)
            .filterIsInstance<MediaSearchResult.TvShow>()

        if (searchResults.isEmpty()) {
            log("No metadata found for TV show.")
            return@indent null
        }

        val canonicalShow = findAndConsolidateBestMatch(searchResults, title, year)
                as? CanonicalMedia.TvShow

        if (canonicalShow != null) {
            cache.putShow(cacheKey, canonicalShow)
        }

        return@indent canonicalShow
    }

    /**
     * Helper function to get an episode's metadata, checking the cache before fetching from providers.
     */
    context(_: LoggingContext)
    private suspend fun findOrFetchEpisode(show: CanonicalMedia.TvShow, season: Int, episode: Int): CanonicalMedia.Episode? = indent {
        // Use a stable ID for the cache key, like TMDB or IMDb ID, falling back to title.
        val showId = show.tmdbId ?: show.imdbId ?: show.title
        val cacheKey = "$showId:$season:$episode"

        val cachedEpisode = cache.getEpisode(cacheKey)
        if (cachedEpisode != null) {
            log("Cache HIT for episode: S${season}E${episode}")
            return@indent cachedEpisode
        }
        log("Cache MISS for episode: S${season}E${episode}. Searching providers...")

        val episodeDetailsResults = metadataService.getEpisode(show, season, episode)
        if (episodeDetailsResults.isEmpty()) {
            return@indent null
        }
        val bestEpisodeMatch = episodeDetailsResults.first() // Assuming the first is the best
        cache.putEpisode(cacheKey, bestEpisodeMatch)

        return@indent bestEpisodeMatch
    }

    context(_: LoggingContext)
    private fun organizeFile(
        source: Path,
        destination: Path,
        media: CanonicalMedia,
        parsedInfo: ParsedMediaInfo,
        mode: OperationMode,
        isTestMode: Boolean,
    ) = run {
        fileOrganizer.organize(source, destination, media, parsedInfo, mode, isTestMode)
            .onSuccess { newPath -> log("Successfully organized at: $newPath") }
            .onFailure { error ->
                log("Error: ${error.message}")
                error.printStackTrace()
            }
    }

    context(_: LoggingContext)
    private fun findAndConsolidateBestMatch(
        results: List<MediaSearchResult>,
        parsedTitle: String,
        parsedYear: String?
    ): CanonicalMedia? = indent {
        if (results.isEmpty()) return@indent null

        log("Consolidating ${results.size} results for title: '$parsedTitle' year: '$parsedYear'")

        val groupedByMedia = results.groupBy {
            val normalizedTitle = it.title.lowercase().replace(Regex("[^a-z0-9]"), "")
            "$normalizedTitle:${it.year}"
        }
        log("${groupedByMedia.size} unique media candidates found.")

        val scoredGroups = groupedByMedia.values.mapNotNull { group ->
            val representative = group.first()

            // Normalize: keep only alphanumeric chars for scoring to handle "Spider-Man" vs "Spiderman"
            val normalizedParsedTitle = parsedTitle.filter { it.isLetterOrDigit() }.lowercase()
            val normalizedGroupTitle = representative.title.filter { it.isLetterOrDigit() }.lowercase()

            var score = 0.0

            val distance = levenshtein(normalizedParsedTitle, normalizedGroupTitle)
            val titleLength = max(normalizedParsedTitle.length, normalizedGroupTitle.length)
            val similarity = if (titleLength > 0) 1.0 - (distance.toDouble() / titleLength) else 0.0

            if (similarity < 0.4) {
                log("Candidate: '${representative.title} (${representative.year})' | Discarded (title similarity too low)")
                return@mapNotNull null
            }

            score += similarity * 10.0

            // Year scoring: Exact(+10), Adjacent(+5), Mismatch(-10)
            if (parsedYear != null) {
                val parsedY = parsedYear.toIntOrNull()
                val groupY = representative.year?.toIntOrNull()
                if (parsedY != null && groupY != null) {
                    val diff = abs(parsedY - groupY)
                    when (diff) {
                        0 -> score += 10.0
                        1 -> score += 5.0
                        else -> score -= 10.0
                    }
                }
            }

            score += (group.distinctBy { it.provider }.size - 1) * 2.0

            // Bonus based on the average confidence score reported by the providers
            val avgProviderConfidence = group.map { it.matchConfidence }.average()
            // We scale it (e.g., divide by 20) to make it a bonus, not the main driver of the score
            score += avgProviderConfidence / 20.0

            log("Candidate: '${representative.title} (${representative.year})' | Score: ${score.format(2)}")
            group to score
        }

        val bestGroup = scoredGroups.maxByOrNull { it.second }
        if (bestGroup == null || bestGroup.second < MINIMUM_CONFIDENCE_SCORE) {
            log("No candidate passed the minimum confidence score of $MINIMUM_CONFIDENCE_SCORE.")
            return@indent null
        }

        log("Best match selected: '${bestGroup.first.first().title}' with score ${bestGroup.second.format(2)}")

        val groupItems = bestGroup.first
        val bestItem = groupItems.firstOrNull { it.year == parsedYear } ?: groupItems.first()

        // Consolidate all IDs from the winning group
        val imdbId = groupItems.firstNotNullOfOrNull { it.imdbId }
        val tmdbId = groupItems.firstNotNullOfOrNull { it.tmdbId }
        val tvdbId = groupItems.firstNotNullOfOrNull { it.tvdbId }

        return@indent when (bestItem) {
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