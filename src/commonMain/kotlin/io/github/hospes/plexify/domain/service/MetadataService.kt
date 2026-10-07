package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.data.MetadataNotFoundException
import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ExternalIds
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import io.github.hospes.plexify.logging.debug
import io.github.hospes.plexify.logging.indent
import io.github.hospes.plexify.logging.log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class MetadataService(
    private val providers: List<MetadataProvider>,
    private val namingStrategy: NamingStrategy,
) {

    context(_: LoggingContext)
    suspend fun search(title: String, year: String?): List<MediaSearchResult> = coroutineScope {
        indent {
            val activeProviders = resolveActiveProviders()
            if (activeProviders.isEmpty()) {
                log("No active metadata providers available.")
                return@coroutineScope emptyList()
            }

            activeProviders.map { provider ->
                async {
                    provider.search(title, year)
                        .onSuccess { results -> debug("Found ${results.size} results from ${provider.id}") }
                        .onFailure { error -> log("Error(${provider.id}): ${error.message}") }
                }
            }.awaitAll().flatMap { it.getOrDefault(emptyList()) }
        }
    }

    /**
     * The season from the first provider that has it. A season the providers report as nonexistent
     * (HTTP 404) comes back empty; null means it could not be fetched (network error, rate limit),
     * which says nothing about whether the show has that season.
     */
    context(_: LoggingContext)
    suspend fun getSeason(show: CanonicalMedia.TvShow, season: Int): CanonicalMedia.Season? {
        return indent {
            val activeProviders = resolveActiveProviders()
            if (activeProviders.isEmpty()) {
                log("No active metadata providers available.")
                return@indent null
            }

            var notFound = false
            var failed = false
            for (provider in activeProviders) {
                val result = provider.season(show, season)
                    .onFailure { error ->
                        // A missing season is routine (split-cour anime): the episode-group fallback
                        // or the per-file outcome line reports it, so keep it out of the concise log.
                        if (error is MetadataNotFoundException) {
                            notFound = true
                            debug("${provider.id}: ${error.message}")
                        } else {
                            if (error !is UnsupportedOperationException) failed = true
                            log("Error(${provider.id}): ${error.message}")
                        }
                    }
                val seasonData = result.getOrNull()
                if (seasonData != null) {
                    debug("Season $season fetched from ${provider.id}")
                    return@indent seasonData
                }
            }
            if (notFound && !failed) CanonicalMedia.Season(show, season, emptyList()) else null
        }
    }

    /** Alternative orderings of the show's episodes, from the first provider that has any. */
    context(_: LoggingContext)
    suspend fun getEpisodeGroups(show: CanonicalMedia.TvShow): List<CanonicalMedia.EpisodeGroup> {
        return indent {
            for (provider in resolveActiveProviders()) {
                val groups = provider.episodeGroups(show)
                    .onFailure { error ->
                        if (error !is UnsupportedOperationException) log("Error(${provider.id}): ${error.message}")
                    }
                    .getOrNull()
                if (!groups.isNullOrEmpty()) {
                    debug("${groups.size} episode group(s) fetched from ${provider.id}")
                    return@indent groups
                }
            }
            emptyList()
        }
    }

    /**
     * IMDb and TVDb IDs of a matched movie or show, from the first provider that supplies one.
     * Null without a lookup when the naming template uses neither (TVDb counts for shows only),
     * so the extra call is only made when its result is used.
     */
    context(_: LoggingContext)
    suspend fun getExternalIds(media: CanonicalMedia): ExternalIds? {
        val applicable = when (media) {
            is CanonicalMedia.Movie -> setOf(IMDB_ID)
            is CanonicalMedia.TvShow -> setOf(IMDB_ID, TVDB_ID)
            else -> emptySet()
        }
        val wanted = applicable intersect namingStrategy.requiredMetadataFields()
        if (wanted.isEmpty()) return null
        return indent {
            for (provider in resolveActiveProviders().filter { p -> p.supportedIds.any { it in wanted } }) {
                val ids = provider.externalIds(media)
                    .onFailure { error ->
                        when (error) {
                            is UnsupportedOperationException -> Unit
                            is MetadataNotFoundException -> debug("${provider.id}: ${error.message}")
                            else -> log("Error(${provider.id}): ${error.message}")
                        }
                    }
                    .getOrNull()
                if (ids != null && (ids.imdbId != null || ids.tvdbId != null)) {
                    debug("External IDs fetched from ${provider.id}: imdb=${ids.imdbId}, tvdb=${ids.tvdbId}")
                    return@indent ids
                }
            }
            null
        }
    }

    context(_: LoggingContext)
    private fun resolveActiveProviders(): List<MetadataProvider> {
        val requiredFields = namingStrategy.requiredMetadataFields()
        val selectedProviders = mutableListOf<MetadataProvider>()

        // 1. Primary Provider: Prefer TMDB.
        // We always need at least one provider to perform the initial search.
        val primary = providers.firstOrNull { it.id == "tmdb" }
            ?: providers.firstOrNull()
            ?: return emptyList()

        selectedProviders.add(primary)

        // 2. Secondary Providers: Add if required by template
        for (provider in providers) {
            if (provider in selectedProviders) continue
            // If the provider supports an ID that is explicitly requested by the template, include it.
            if (provider.supportedIds.any { it in requiredFields }) {
                selectedProviders.add(provider)
            }
        }

        return selectedProviders
    }
}

// Template placeholders (lowercased, as requiredMetadataFields() reports them) for external IDs.
private const val IMDB_ID = "imdbid"
private const val TVDB_ID = "tvdbid"
