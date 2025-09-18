package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.service.MovieFilenameParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.Path

class MediaProcessor(
    private val metadataProviders: List<MetadataProvider>,
    private val fileOrganizer: FileOrganizer,
) {
    suspend fun process(source: Path, destination: Path, mode: OperationMode) {
        println("Processing: $source")

        // 1. Parse
        val parsedInfo = MovieFilenameParser.parse(source.name)
        val parsedDetails = listOfNotNull(
            "Title='${parsedInfo.title}'",
            parsedInfo.year?.let { "Year='$it'" },
            parsedInfo.resolution?.let { "Resolution='$it'" },
            parsedInfo.quality?.let { "Quality='$it'" },
            parsedInfo.releaseGroup?.let { "ReleaseGroup='$it'" }
        ).joinToString(", ")
        println("  -> Parsed as: $parsedDetails")

        // 2. Search
        val searchResults = coroutineScope {
            metadataProviders.map { provider ->
                async { provider.search(parsedInfo.title, parsedInfo.year) }
            }.awaitAll().flatMap { it.getOrDefault(emptyList()) }
        }

        if (searchResults.isEmpty()) {
            println("  -> No metadata found.")
            return
        }

        // 3. Consolidate/Choose Best Match (simple logic for now)
        val canonicalMedia = consolidateAndSelectBestMatch(searchResults, parsedInfo.year)

        if (canonicalMedia == null) {
            println("  -> Could not find a confident match from search results.")
            return
        }

        val printList = canonicalMedia.let { media ->
            listOfNotNull(
                media.title,
                media.year.let { "(${it})" },
                media.imdbId?.let { "[imdbid-$it]" },
                media.tmdbId?.let { "[tmdbid-$it]" },
            )
        }
        println("  -> Found match: ${printList.joinToString(" ")}")


        // 4. Organize
        fileOrganizer.organize(source, destination, canonicalMedia, parsedInfo, mode)
            .onSuccess { newPath -> println("  -> Successfully organized at: $newPath") }
            .onFailure { error -> println("  -> Error: ${error.message}") }
    }

    private fun consolidateAndSelectBestMatch(
        results: List<MediaSearchResult>,
        parsedYear: String?
    ): CanonicalMedia? {
        if (results.isEmpty()) return null

        // --- MERGER LOGIC ---
        // Group results by title and year. This treats results from different providers
        // for the same movie as a single entity.
        val groupedByMovie = results.groupBy {
            // Normalize title for better grouping
            val normalizedTitle = it.title.lowercase().replace(Regex("[^a-z0-9]"), "")
            "$normalizedTitle:${it.year}"
        }

        // Score each group to find the best candidate.
        val bestGroup = groupedByMovie.values.maxByOrNull { group ->
            var score = 0
            // Higher score for more providers agreeing on this movie
            score += group.distinctBy { it.provider }.size * 2
            // Higher score if the year matches the one from the filename
            if (parsedYear != null && group.any { it.year == parsedYear }) {
                score += 5
            }
            // Higher score for having an IMDb ID, as it's a great identifier
            if (group.any { it.imdbId != null }) {
                score += 3
            }
            score
        } ?: return null

        // Now, consolidate the information from the best group into one CanonicalMedia object.
        return CanonicalMedia(
            // Prefer a title from a result that matches the parsed year, otherwise take the first.
            title = bestGroup.firstOrNull { it.year == parsedYear }?.title ?: bestGroup.first().title,
            year = (bestGroup.firstOrNull { it.year == parsedYear }?.year ?: bestGroup.first().year)?.toIntOrNull() ?: 0,
            // Collect all unique IDs from the group.
            imdbId = bestGroup.firstNotNullOfOrNull { it.imdbId },
            tmdbId = bestGroup.firstNotNullOfOrNull { it.tmdbId }
        )
    }
}