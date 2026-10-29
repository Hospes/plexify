package io.github.hospes.plexify.core

import io.github.hospes.plexify.domain.matching.MatchScoring
import io.github.hospes.plexify.domain.matching.RankedMatch
import io.github.hospes.plexify.domain.model.CanonicalMedia
import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.service.EpisodeGroupMapper
import io.github.hospes.plexify.logging.LoggingContext
import io.github.hospes.plexify.logging.debug
import io.github.hospes.plexify.logging.indent
import io.github.hospes.plexify.logging.status
import kotlinx.io.files.Path

/**
 * The provider episode an episode file is, once its show is matched: the season (as the file numbers it, as the show's
 * title names it, or season 1), the episode in the best-matching show or, when that show lacks the season, in a
 * same-titled runner-up, else through the show's episode groups, and the whole range of a multi-episode file.
 *
 * It prints what it decides as it goes, as the rest of the pipeline does: the [metadata] calls print their cache lines
 * in between, so messages handed back to the caller would print out of order. What skips the file, it returns
 * ([Resolution.Skipped]) for the caller to report and count. What the run decided about each show is in [showStates],
 * shared with the show lookup that fills it.
 */
internal class EpisodeResolver(
    private val metadata: MetadataRepository,
    private val showStates: ShowStates,
) {
    sealed interface Resolution {
        data class Found(val episode: CanonicalMedia.Episode) : Resolution

        /** The file has no episode; [reason] says why. */
        data class Skipped(val reason: String) : Resolution
    }

    // Directories already warned about missing season numbers, to avoid repeating the
    // warning for every episode file of the same release.
    private val seasonWarnedDirs = mutableSetOf<String>()

    // Provider shows (by identity) and seasons already reported as mapped (or unmappable) through episode groups.
    private val episodeGroupNotices = mutableSetOf<Pair<ShowIdentity, Int>>()

    /**
     * The episode [source], read as [parsed], is in [candidates] (the matched shows, best first), whose decisions are
     * kept under [showKey]. Fails when a season or a lookup it needs could not be loaded: that says nothing about the
     * episode, so the file is a failure, not a miss.
     */
    context(_: LoggingContext)
    suspend fun resolve(
        source: Path,
        parsed: ParsedMediaInfo.Episode,
        candidates: List<CanonicalMedia.TvShow>,
        showKey: ShowKey,
    ): Result<Resolution> {
        // A release named after one season's own title (an anime arc, numbered from 1 with no
        // season in the filenames) is that season, not season 1.
        val state = showStates[showKey]
        val season = parsed.season ?: state.titleSeason?.let { named ->
            if (!state.titleSeasonReported) {
                state.titleSeasonReported = true
                status("No season number in filenames; '${named.title}' is Season ${named.season} of ${candidates.first().describe()}")
            }
            named.season
        } ?: run {
            if (state.namesUnplacedArc) {
                val reason = "'${parsed.showTitle}' names an arc that matches no season title of ${candidates.first().describe()}; set the season with -s/--season"
                return Result.success(Resolution.Skipped(reason))
            }
            if (seasonWarnedDirs.add(source.parent?.toString() ?: source.name)) {
                status("Warning: No season number found in filenames, defaulting to Season 1 (use -s/--season to set it explicitly).")
            }
            1
        }

        // The episode, via a season-level fetch (fills the whole season's cache in one call).
        val seasonKey = SeasonKey(showKey, season)
        val first = findEpisode(candidates, seasonKey, parsed.episode).getOrElse { return Result.failure(it) }
            ?: return Result.success(Resolution.Skipped("episode S${season}E${parsed.episode} not found"))
        val episode = parsed.lastEpisode
            ?.let { last -> extendToRange(first, candidates, seasonKey, parsed.episode, last, source.name).getOrElse { return Result.failure(it) } }
            ?: first
        return Result.success(Resolution.Found(episode))
    }

    /**
     * Extends the first episode of a multi-episode file ("S01E01-E03") to its whole range. Every other
     * episode is looked up like the first (same show, cached season, episode groups); the range is kept
     * only when they land on consecutive episodes of one provider season, else the file is filed as its
     * first episode. Fails as [findEpisode] does.
     */
    context(_: LoggingContext)
    private suspend fun extendToRange(
        first: CanonicalMedia.Episode,
        candidates: List<CanonicalMedia.TvShow>,
        seasonKey: SeasonKey,
        firstEpisode: Int,
        lastEpisode: Int,
        fileName: String,
    ): Result<CanonicalMedia.Episode> {
        val season = seasonKey.season
        val episodes = mutableListOf(first)
        for (episode in (firstEpisode + 1)..lastEpisode) {
            val match = findEpisode(candidates, seasonKey, episode).getOrElse { return Result.failure(it) }
            val previous = episodes.last()
            if (match == null || match.season != previous.season || match.episode != previous.episode + 1) {
                status(
                    "Warning: ${fileName} — S${season.pad2()}E${episode.pad2()} is not the episode after " +
                            "S${previous.season.pad2()}E${previous.episode.pad2()} in '${first.show.title}'; " +
                            "filing it as S${first.season.pad2()}E${first.episode.pad2()} only."
                )
                return Result.success(first)
            }
            episodes += match
        }
        return Result.success(
            first.copy(
                lastEpisode = episodes.last().episode,
                title = episodes.map { it.title }.distinct().joinToString(" & "),
            )
        )
    }

    /**
     * Finds the episode in the best-matching show. When that show has no such season at all, it is
     * not the release's show (e.g. a same-titled spin-off with one season), so the same-titled
     * runner-up shows are tried and the first that has the exact episode is used. The show that places a season is
     * kept for the rest of that season's files, so one season never splits across shows. Fails when a
     * season could not be loaded: that says nothing about the episode, so the file is a failure, not a miss.
     */
    context(_: LoggingContext)
    private suspend fun findEpisode(
        candidates: List<CanonicalMedia.TvShow>,
        seasonKey: SeasonKey,
        episode: Int,
    ): Result<CanonicalMedia.Episode?> {
        val season = seasonKey.season
        // A runner-up's season was fetched before its external IDs were, so its cached episodes
        // carry the show without them: hand out the season's show as decided.
        showStates.seasonShow(seasonKey)?.let { show -> return findOrFetchEpisode(show, season, episode).map { it?.copy(show = show) } }

        val primary = candidates.first()
        val primaryMatch = findOrFetchEpisode(primary, season, episode).getOrElse { return Result.failure(it) }
        if (primaryMatch != null) {
            showStates.placeSeason(seasonKey, primary)
            return Result.success(primaryMatch)
        }

        // The show has the season, just not this episode: a numbering quirk, not evidence of a wrong show.
        if (!metadata.isSeasonMissing(primary, season)) return Result.success(null)

        for (candidate in candidates.drop(1).take(MatchScoring.MAX_FALLBACK_SHOWS)) {
            // A runner-up whose season fails to load fails the file: whether it has the season is
            // unknown, and taking the next one could file the season under the wrong show.
            val candidateSeason = indent { metadata.season(candidate, season) }.getOrElse { return Result.failure(it) }
            val match = candidateSeason.episodes.firstOrNull { it.episode == episode } ?: continue
            val alternative = metadata.withImdbId(candidate).getOrElse { return Result.failure(it) }
            showStates.placeSeason(seasonKey, alternative)
            status("Season $season is not in ${primary.describe()}; matched show: ${alternative.describe()}")
            return Result.success(match.copy(show = alternative))
        }
        return Result.success(null)
    }

    /**
     * Fetches the whole season (one API call) and returns the requested episode.
     * Subsequent episodes from the same season are served from cache.
     */
    context(_: LoggingContext)
    private suspend fun findOrFetchEpisode(show: CanonicalMedia.TvShow, season: Int, episode: Int): Result<CanonicalMedia.Episode?> = indent {
        val cachedSeason = metadata.season(show, season).getOrElse { return@indent Result.failure(it) }

        val match = cachedSeason.episodes.firstOrNull { it.episode == episode }
        if (match != null) return@indent Result.success(match)

        debug("Episode E${episode} not found in S${season} data. Trying episode groups...")
        findViaEpisodeGroups(show, season, episode, providerHasSeason = cachedSeason.episodes.isNotEmpty())
    }

    /**
     * Fallback for releases numbered differently from the provider, typically anime split into
     * cours: the release's S2E01 is TMDB's S1E13. Episode groups (fetched once per show) record
     * that split; the episode found is the provider's own, so the file gets TMDB's numbering. Fails
     * when the groups could not be loaded; a failed lookup is not cached, so the next file tries again.
     * [providerHasSeason] is false when the providers reported the season as nonexistent.
     */
    context(_: LoggingContext)
    private suspend fun findViaEpisodeGroups(
        show: CanonicalMedia.TvShow,
        season: Int,
        episode: Int,
        providerHasSeason: Boolean,
    ): Result<CanonicalMedia.Episode?> = indent {
        val groups = metadata.episodeGroups(show).getOrElse { return@indent Result.failure(it) }

        val match = when (val resolution = EpisodeGroupMapper.resolve(groups, season, episode, providerHasSeason)) {
            is EpisodeGroupMapper.Resolution.Found -> {
                val mapped = resolution.episode
                if (episodeGroupNotices.add(show.identity to season)) {
                    status(
                        "Season $season of '${show.title}' mapped through episode group '${resolution.group.name}' " +
                                "(S${season.pad2()}E${episode.pad2()} → S${mapped.season.pad2()}E${mapped.episode.pad2()})"
                    )
                }
                debug("Mapped S${season}E${episode} → S${mapped.season}E${mapped.episode} via '${resolution.group.name}' part '${resolution.part.name}'")
                mapped
            }

            is EpisodeGroupMapper.Resolution.Ambiguous -> {
                if (episodeGroupNotices.add(show.identity to season)) {
                    val names = resolution.groups.joinToString(", ") { "'${it.name}'" }
                    status(
                        "Season $season of '${show.title}' is numbered differently by episode groups $names; " +
                                "use -s/--season and --episode-offset to place it."
                    )
                }
                null
            }

            EpisodeGroupMapper.Resolution.NotFound -> {
                debug("No episode group places S${season}E${episode} (${groups.size} group(s) checked).")
                null
            }
        }
        Result.success(match)
    }
}

/**
 * Whether this runner-up may take a season the best match lacks (see [EpisodeResolver]): only a show named as the
 * release is, as closely as the best match (a same-titled show, like Being Human UK and US), and not contradicted by
 * the filename year. A merely similar title clears the confidence minimum too, but it is a different show.
 */
internal fun RankedMatch.canStandInFor(best: RankedMatch): Boolean =
    titleSimilarity >= best.titleSimilarity && (yearScore == null || yearScore > 0)
