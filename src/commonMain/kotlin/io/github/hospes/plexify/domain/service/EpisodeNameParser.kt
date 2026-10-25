package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.service.ReleaseTags.isReleaseYear

/**
 * Reads an episode's name by tiers, the first that matches winning: SxxExx or 1x01 (1), a fansub " - 01" (2a), a
 * season marker with a [NN] episode (2), a [NN] episode with the season from the folder (3) or none (4).
 */
internal object EpisodeNameParser {

    // --- Regex for TV Show Episode Extraction ---
    // Tier 1: Captures S01E01, s01e01, S1E1, S01.E01, S01E100, S01E1071, S2024E01, a revision (S01E05v2), and
    // multi-episode files: S01E01E02, S01E01-E02, S01E01-02 (group 4 holds the range suffix). The marker can
    // start the name ("S01E01 - Pilot") and be followed by a tag ("S01E01[1080p]").
    private val episodeRegex =
        """(?:^|[._\-\s\[(])([Ss](\d{1,4})[._\s]?[Ee](\d{1,4})((?:-?[Ee]\d{1,4}|-\d{1,3})*))(?:v\d{1,2})?(?=[._\-\s\[\]()]|$)""".toRegex()

    // Tier 1b: 1x01 / 01x01, and 1x01-02 / 1x01x02 / 1x01-1x02. The episode needs two digits, so a
    // title like "3x3 Eyes" isn't read as one, and the season must start a token ("1920x1080" doesn't).
    private val crossEpisodeRegex =
        """(?:^|[._\-\s\[(])((\d{1,2})[xX](\d{2,3})((?:[-xX]\d{2,3}|-\d{1,2}[xX]\d{2,3})*))(?=[._\-\s\[\]()]|$)""".toRegex()

    // The numbers in a range suffix; the last one ends the range
    private val rangeNumberRegex = """\d+""".toRegex()

    // Tier 2a: anime fansub style "[SubsPlease] Show - 01 (1080p) [ABCD1234]", "Show - 01v2", absolute
    // "Show - 1071". A year is not an episode: neither a 19xx/20xx number ("Alien - 1979") nor a number
    // with a bracketed year after it, which makes the name a movie's ("Title - 6 (2013)", "Star Wars - 4 - A
    // New Hope (1977)").
    private val dashEpisodeRegex =
        """\s-\s(?!(?:19|20)\d{2}(?:\D|$))(\d{1,4})(?:v\d{1,2})?(?=\s|$|[\[(])(?!.*[\[(](?:19|20)\d{2}[\])])""".toRegex()

    // A season marker closing a fansub title: "Show S2", "Show Season 2", "Show 2nd Season"
    private val trailingSeasonRegex =
        """\s(?:S(\d{1,2})|Season\s+(\d{1,2})|(\d{1,2})(?:st|nd|rd|th)\s+Season)$""".toRegex(RegexOption.IGNORE_CASE)

    // A number closing a show title that may be its season: "Devil May Cry 2", "Shuumatsu no Walkure III"
    private val trailingNumberRegex = """^(.*\p{L}.*?)\s+(\d{1,2}|II|III|IV|V|VI|VII|VIII|IX|X)$""".toRegex(RegexOption.IGNORE_CASE)
    private val romanNumerals = listOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x")

    // Tier 2: season marker in the (normalized) filename: "Season 2", or a bare "S2" token as in "Gate_S2_[01]"
    private val seasonKeywordRegex = """(?:^|\s)(?:Season\s+|S)(\d{1,2})(?:\s|$)""".toRegex(RegexOption.IGNORE_CASE)

    // Tiers 2-4: [NN] bracket episode — 1-3 digits only (avoids matching [1080p] which contains letters).
    // AniLibria marks a season's last episode "[26_END]", which reads "[26 END]" once normalized. Square
    // brackets only: a number in parentheses is a title's ("(500) Days of Summer") or the " (1)" a browser
    // adds to a second download of the same name.
    private val bracketEpisodeRegex = """\[(\d{1,3})(?:\s+END)?]""".toRegex(RegexOption.IGNORE_CASE)

    // Tier 3: Season number from parent directory name ("Season 2", "S02", "Show.S2", "Gate_S2_[720p]"). The
    // marker must start a Latin word: the "s" closing "(Episodes 13-24)" or "(Eps 1-8)" is not one, while
    // one glued to a title in another script still is ("進撃の巨人S2").
    private val seasonFromDirRegex = """(?<![A-Za-z0-9])(?:Season|S)\s*(\d{1,2})(?![\p{L}\p{N}])""".toRegex(RegexOption.IGNORE_CASE)

    // "Doctor.Who.2005": a bare year closing a scene show name, unless it is all of it ("1923")
    private val trailingBareYearRegex = """[._\s](19\d{2}|20\d{2})$""".toRegex()

    /**
     * The episode [workingFilename] names (the file name without its extension and provider-ID tags), with the season
     * from [parentDirName] when the name has none; null when no tier reads it as one.
     */
    fun parse(workingFilename: String, parentDirName: String?): ParsedMediaInfo.Episode? {
        // Normalize once; all tier regexes run against this
        val normalized = workingFilename.replace('.', ' ').replace('_', ' ')
        val leadingGroup by lazy { ReleaseGroupReader.leadingGroup(workingFilename) }
        val sceneGroup by lazy { ReleaseGroupReader.sceneGroupOf(workingFilename) }

        // --- Tier 1: Standard SxxExx, then 1x01 ---
        val episodeMatch = episodeRegex.find(workingFilename) ?: crossEpisodeRegex.find(workingFilename)
        if (episodeMatch != null) {
            val showPart = workingFilename.substring(0, episodeMatch.range.first).trimEnd(' ', '-', '.', '_')
            val bracketedYear = ReleaseTags.trailingYearRegex.find(showPart)
            val bareYear = if (bracketedYear != null) null else trailingBareYearRegex.find(showPart)?.takeIf {
                it.groupValues[1].isReleaseYear() && cleanShowTitle(showPart.substring(0, it.range.first)).isNotEmpty()
            }
            val showYear = bracketedYear ?: bareYear
            val showTitle = cleanShowTitle(showYear?.let { showPart.substring(0, it.range.first) } ?: showPart)

            // Plexify's own names: "Show (2015) - S01E13 - Episode Title - [720p] [HDTV]". The episode
            // title is free text, so read metadata only from the show part and the {version} suffix. Without
            // that suffix the name can be another tool's, with the tags after the episode title ("Pilot
            // WEBDL-1080p", as Sonarr writes it), so the tags come from the whole name, but not the edition:
            // an episode is as likely to be titled "The Final Cut".
            val afterEpisode = workingFilename.substring(episodeMatch.range.last + 1)
            val versionSuffix = ReleaseTags.versionSuffixRegex.find(afterEpisode)?.value
            val titledEpisode = afterEpisode.trimStart().startsWith("- ")
            val metadataText = if (titledEpisode && versionSuffix != null) showPart + versionSuffix else workingFilename
            val editionText = if (titledEpisode) showPart + (versionSuffix ?: "") else workingFilename

            val episode = episodeMatch.groupValues[3].toInt()
            return buildEpisode(
                showTitle = showTitle,
                season = episodeMatch.groupValues[2].toInt(),
                episode = episode,
                lastEpisode = rangeEnd(episode, episodeMatch.groupValues[4]),
                normalized = metadataText.replace('.', ' ').replace('_', ' '),
                editionText = editionText.replace('.', ' ').replace('_', ' '),
                year = showYear?.groupValues?.get(1),
                bareShowYear = bareYear != null,
                leadingGroup = leadingGroup,
                // In a titled name the episode title before the tags is free text ("A Proper Send-Off"): only
                // Sonarr's "[h264]-NTb" counts there, and in plexify's own names the {version} suffix holds it.
                sceneGroup = when {
                    !titledEpisode -> sceneGroup
                    versionSuffix == null -> ReleaseGroupReader.sceneGroupOf(workingFilename, afterBracketOnly = true)
                    else -> ReleaseGroupReader.versionSuffixGroup(versionSuffix)
                },
            )
        }

        // --- Tier 2a: fansub "[Group] Show - 01 (1080p) [CRC]"; season from the title, else the parent directory ---
        // Dots stay: "Show - 12.5" is a recap special, not episode 12
        val dashText = workingFilename.replace('_', ' ')
        val dashMatch = dashEpisodeRegex.find(dashText)
        if (dashMatch != null) {
            var showPart = dashText.substring(0, dashMatch.range.first).replace(ReleaseGroupReader.leadingGroupsRegex, "").trim()
            val seasonInTitle = trailingSeasonRegex.find(showPart)
            if (seasonInTitle != null) showPart = showPart.substring(0, seasonInTitle.range.first).trimEnd()
            val showYear = ReleaseTags.trailingYearRegex.find(showPart)
            if (showYear != null) showPart = showPart.substring(0, showYear.range.first)

            val showTitle = cleanShowTitle(showPart)
            return buildEpisode(
                showTitle = showTitle,
                season = seasonInTitle?.groupValues?.drop(1)?.first { it.isNotEmpty() }?.toInt()
                    ?: seasonFromDir(parentDirName),
                episode = dashMatch.groupValues[1].toInt(),
                normalized = normalized,
                year = showYear?.groupValues?.get(1),
                leadingGroup = leadingGroup,
                sceneGroup = sceneGroup,
                numberedSeason = if (seasonInTitle == null) numberedSeason(showTitle) else null,
            )
        }

        // --- Tier 2: "Season N" / "SN" marker in filename + [NN] bracket episode ---
        val seasonKeywordMatch = seasonKeywordRegex.find(normalized)
        if (seasonKeywordMatch != null) {
            val season = seasonKeywordMatch.groupValues[1].toInt()
            val afterSeason = normalized.substring(seasonKeywordMatch.range.last + 1)
            val bracketEpMatch = bracketEpisodeRegex.find(afterSeason)
            if (bracketEpMatch != null) {
                return buildEpisode(
                    showTitle = cleanShowTitle(normalized.substring(0, seasonKeywordMatch.range.first)),
                    season = season,
                    episode = bracketEpMatch.groupValues[1].toInt(),
                    normalized = normalized,
                    leadingGroup = leadingGroup,
                    sceneGroup = sceneGroup,
                )
            }
        }

        // --- Tiers 3 & 4: bracket episode [NN] ---
        val bracketEpMatch = bracketEpisodeRegex.find(normalized)
        if (bracketEpMatch != null) {
            val showTitle = cleanShowTitle(normalized.substring(0, bracketEpMatch.range.first))
            return buildEpisode(
                showTitle = showTitle,
                // Tier 3: season from parent directory name; null when there is none (Tier 4)
                season = seasonFromDir(parentDirName),
                episode = bracketEpMatch.groupValues[1].toInt(),
                normalized = normalized,
                leadingGroup = leadingGroup,
                sceneGroup = sceneGroup,
                numberedSeason = numberedSeason(showTitle),
            )
        }

        // No tier matched: a movie's name
        return null
    }

    private fun seasonFromDir(parentDirName: String?): Int? =
        parentDirName?.let { seasonFromDirRegex.find(it) }?.groupValues?.get(1)?.toIntOrNull()

    // The end of a multi-episode range suffix ("E02", "-E02", "-02", "x02", "-1x02"); null unless it is
    // past the first episode.
    private fun rangeEnd(firstEpisode: Int, rangeSuffix: String): Int? =
        rangeNumberRegex.findAll(rangeSuffix).lastOrNull()?.value?.toInt()?.takeIf { it > firstEpisode }

    // The show title without its closing number, and the number as a season ("Devil May Cry 2" is Devil May
    // Cry season 2); null when it has none, or only the number ("86"). Season 0 is specials, never a sequel,
    // and a number right after another is a fraction or a range ("Ranma 1-2" is Ranma ½), not a season.
    private fun numberedSeason(showTitle: String): ParsedMediaInfo.NumberedSeason? {
        val match = trailingNumberRegex.find(showTitle) ?: return null
        val title = match.groupValues[1]
        val number = match.groupValues[2].lowercase()
        val arabic = number.toIntOrNull()
        if (arabic != null && title.substringAfterLast(' ').all { it.isDigit() }) return null
        val season = arabic ?: (romanNumerals.indexOf(number) + 1)
        if (season < 1) return null
        return ParsedMediaInfo.NumberedSeason(title.lowercase(), season)
    }

    // Leading release group tags ("[SubsPlease] Show") are not part of the show title.
    private fun cleanShowTitle(rawTitle: String): String =
        rawTitle.replace(ReleaseGroupReader.leadingGroupsRegex, " ").replace(ReleaseTags.delimiterRegex, " ")
            .replace(ReleaseTags.cleanupRegex, " ").trim()

    // Metadata extractors must run on the normalized text: '_' is a word character, so `\b` never
    // matches between '_' and a tag like "720p" in underscore-separated names.
    private fun buildEpisode(
        showTitle: String,
        season: Int?,
        episode: Int,
        normalized: String,
        editionText: String = normalized,
        lastEpisode: Int? = null,
        year: String? = null,
        bareShowYear: Boolean = false,
        leadingGroup: String? = null,
        sceneGroup: String? = null,
        numberedSeason: ParsedMediaInfo.NumberedSeason? = null,
    ) =
        ParsedMediaInfo.Episode(
            showTitle = showTitle.lowercase(),
            season = season,
            episode = episode,
            lastEpisode = lastEpisode,
            year = year ?: episodeYear(normalized, showTitle),
            resolution = ReleaseTags.resolutionIn(normalized),
            quality = ReleaseTags.qualityIn(normalized),
            hdr = ReleaseTags.hdrIn(normalized),
            releaseGroup = ReleaseGroupReader.knownGroup(normalized) ?: leadingGroup ?: sceneGroup,
            edition = ReleaseTags.editionIn(editionText),
            bareShowYear = bareShowYear,
            numberedSeason = numberedSeason,
        )

    // The first year outside the show's own title: "1923.S01E01" has none (the show premiered in 2022),
    // "The.Boys.S03E01.2022" has 2022.
    private fun episodeYear(normalized: String, showTitle: String): String? {
        val titleWords = showTitle.split(' ').toSet()
        return ReleaseTags.yearRegex.findAll(normalized).map { it.value }.firstOrNull { it !in titleWords && it.isReleaseYear() }
    }
}
