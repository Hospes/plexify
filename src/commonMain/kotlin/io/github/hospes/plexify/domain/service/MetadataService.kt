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

    /**
     * Results from every active provider. Fails with a provider's error (timeout, HTTP error, network
     * error) when nothing came back and a provider failed: no results then says nothing about the
     * title, and the caller reports it. Errors from a provider are only logged when another one
     * returned results.
     */
    context(_: LoggingContext)
    suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>> =
        searchAll { search(title, year) }

    /** Movies with a release in [year], from every active provider; fails as [search] does. */
    context(_: LoggingContext)
    suspend fun searchMovies(title: String, year: String): Result<List<MediaSearchResult>> =
        searchAll { searchMovies(title, year) }

    context(_: LoggingContext)
    private suspend fun searchAll(
        query: suspend MetadataProvider.() -> Result<List<MediaSearchResult>>,
    ): Result<List<MediaSearchResult>> = coroutineScope {
        indent {
            val activeProviders = resolveActiveProviders()
            if (activeProviders.isEmpty()) {
                log("No active metadata providers available.")
                return@coroutineScope Result.success(emptyList())
            }

            val outcomes = activeProviders.map { provider -> async { provider to provider.query() } }.awaitAll()
            val results = outcomes.flatMap { (_, outcome) -> outcome.getOrDefault(emptyList()) }
            val failure = outcomes.firstNotNullOfOrNull { (_, outcome) -> outcome.exceptionOrNull() }
            if (results.isEmpty() && failure != null) return@coroutineScope Result.failure(failure)

            for ((provider, outcome) in outcomes) {
                outcome
                    .onSuccess { found -> debug("Found ${found.size} results from ${provider.id}") }
                    .onFailure { error -> log("Error(${provider.id}): ${error.message}") }
            }
            Result.success(results)
        }
    }

    /**
     * The season from the first provider that has it. A season the providers report as nonexistent
     * (HTTP 404) comes back empty. Fails when it could not be fetched (timeout, rate limit, network
     * error), which says nothing about whether the show has that season; the caller reports the error.
     */
    context(_: LoggingContext)
    suspend fun getSeason(show: CanonicalMedia.TvShow, season: Int): Result<CanonicalMedia.Season> = indent {
        var notFound = false
        var failure: Throwable? = null
        for (provider in resolveActiveProviders()) {
            provider.season(show, season)
                .onSuccess { seasonData ->
                    debug("Season $season fetched from ${provider.id}")
                    return@indent Result.success(seasonData)
                }
                .onFailure { error ->
                    // A missing season is routine (split-cour anime): the episode-group fallback or the
                    // per-file outcome line reports it, as the caller does a failed fetch.
                    debug("${provider.id}: ${error.message}")
                    when (error) {
                        is MetadataNotFoundException -> notFound = true
                        is UnsupportedOperationException -> Unit
                        else -> if (failure == null) failure = error
                    }
                }
        }
        failure?.let { return@indent Result.failure(it) }
        if (notFound) Result.success(CanonicalMedia.Season(show, season, emptyList()))
        else Result.failure(IllegalStateException("No metadata provider can fetch seasons."))
    }

    /**
     * Alternative orderings of the show's episodes, from the first provider that has any; empty when
     * none has. Fails as [getSeason] does when a lookup failed (timeout, rate limit, network error)
     * and no provider had any: that says nothing about whether the show has groups.
     */
    context(_: LoggingContext)
    suspend fun getEpisodeGroups(show: CanonicalMedia.TvShow): Result<List<CanonicalMedia.EpisodeGroup>> = indent {
        var failure: Throwable? = null
        for (provider in resolveActiveProviders()) {
            provider.episodeGroups(show)
                .onSuccess { groups ->
                    if (groups.isNotEmpty()) {
                        debug("${groups.size} episode group(s) fetched from ${provider.id}")
                        return@indent Result.success(groups)
                    }
                }
                .onFailure { error ->
                    // The caller reports a failed lookup with the file it fails.
                    debug("${provider.id}: ${error.message}")
                    when (error) {
                        is MetadataNotFoundException, is UnsupportedOperationException -> Unit
                        else -> if (failure == null) failure = error
                    }
                }
        }
        failure?.let { Result.failure(it) } ?: Result.success(emptyList())
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
