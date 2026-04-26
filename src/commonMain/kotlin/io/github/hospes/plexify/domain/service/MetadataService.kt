package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.data.MetadataProvider
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
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
                        .onSuccess { results -> log("Found ${results.size} results from ${provider.id}") }
                        .onFailure { error -> log("Error(${provider.id}): ${error.message}") }
                }
            }.awaitAll().flatMap { it.getOrDefault(emptyList()) }
        }
    }

    context(_: LoggingContext)
    suspend fun getSeason(show: CanonicalMedia.TvShow, season: Int): CanonicalMedia.Season? {
        return indent {
            val activeProviders = resolveActiveProviders()
            if (activeProviders.isEmpty()) {
                log("No active metadata providers available.")
                return@indent null
            }

            for (provider in activeProviders) {
                val result = provider.season(show, season)
                    .onFailure { error -> log("Error(${provider.id}): ${error.message}") }
                val seasonData = result.getOrNull()
                if (seasonData != null) {
                    log("Season $season fetched from ${provider.id}")
                    return@indent seasonData
                }
            }
            null
        }
    }

    context(_: LoggingContext)
    private fun resolveActiveProviders(): List<MetadataProvider> {
        val requiredFields = namingStrategy.requiredMetadataFields()
        val selectedProviders = mutableListOf<MetadataProvider>()

        // 1. Primary Provider: Prefer TMDB, then IMDb. 
        // We always need at least one provider to perform the initial search.
        val primary = providers.firstOrNull { it.id == "tmdb" }
            ?: providers.firstOrNull { it.id == "imdb" }
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