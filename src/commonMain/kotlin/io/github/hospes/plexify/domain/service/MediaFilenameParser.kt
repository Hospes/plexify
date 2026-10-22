package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

object MediaFilenameParser {

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

    // Release group tags leading the name, as fansubs write them: "[SubsPlease] Show - 01"
    private val leadingGroupsRegex = """^(?:\s*\[[^\[\]]*])+""".toRegex()
    private val leadingGroupRegex = """^\s*\[([^\[\]]+)]""".toRegex()
    // A site advertising itself in the leading tag ("[ www.Torrenting.com ] - Show"), not a group
    private val bracketTagRegex = """\[([^\[\]]+)]""".toRegex()
    private val websiteRegex ="""^www\.|\.(?:com|net|org|info|io|to|cc|cd|me|ws)$""".toRegex(RegexOption.IGNORE_CASE)

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

    // --- Regex for Year Extraction ---
    private val yearInBracketsRegex = """[\[(](19\d{2}|20\d{2})[\])]""".toRegex()
    private val yearRegex = """\b(19\d{2}|20\d{2})\b""".toRegex()
    // "Show (2015)": a parenthesized year closing the show title, as in plexify's own episode names
    private val trailingYearRegex = """\((19\d{2}|20\d{2})\)$""".toRegex()
    // "Doctor.Who.2005": a bare year closing a scene show name, unless it is all of it ("1923")
    private val trailingBareYearRegex = """[._\s](19\d{2}|20\d{2})$""".toRegex()

    // The latest year a release can carry: next year's films already circulate as festival copies and
    // screeners. A later one is a title word, as in "Blade.Runner.2049.1080p".
    private val latestReleaseYear = Clock.System.now().toLocalDateTime(TimeZone.UTC).year + 1
    private fun String.isReleaseYear(): Boolean = toInt() <= latestReleaseYear

    // --- Names plexify itself writes ---
    // Provider-ID tags: the [tmdbid-27205] / {imdb-tt1375666} forms our templates produce, [imdbid-tt1375666] from
    // libraries made by 0.5.1 or earlier, plus the {tmdb-27205} / [tmdbid=27205] forms Plex and Jellyfin also read.
    // Never part of the title.
    private val providerIdTagRegex = """[\[{](?:tmdb|imdb|tvdb)(?:id)?[-=][^\]}]*[\]}]""".toRegex(RegexOption.IGNORE_CASE)
    // Plex's edition tag: "Blade Runner (1982) {edition-Final Cut}"
    private val plexEditionTagRegex = """\{edition-[^}]*}""".toRegex(RegexOption.IGNORE_CASE)
    // The {version} suffix PathFormatter appends: " - [1080p] [BluRay] [Extended]"
    private val versionSuffixRegex = """\s-\s(?:\[[^\[\]]+]\s*)+$""".toRegex()

    // --- Specific information to extract ---
    private val resolutionTags = listOf("480p", "720p", "1080p", "2160p", "4k")
    private val resolutionRegex = "\\b(${resolutionTags.joinToString("|")})\\b".toRegex(RegexOption.IGNORE_CASE)

    private val qualityTags = listOf(
        "bluray", "blu-ray", "bdrip", "brrip", "dvd", "dvdrip",
        "web-dl", "webdl", "web-rip", "webrip", "hdtv", "hdrip", "web-dlrip",
    )
    private val qualityRegex = "\\b(${qualityTags.joinToString("|")})\\b".toRegex(RegexOption.IGNORE_CASE)

    // Order matters: more specific variants must come before shorter ones (HDR10+ before HDR10 before HDR).
    private val hdrCanonical = listOf("HDR10+", "HDR10", "HDR", "DV", "DoVi", "HLG", "SDR")
    private val hdrLookup: Map<String, String> = hdrCanonical.associateBy { it.lowercase() }
    // Trailing \b can't follow '+' (non-word char), so use (?!\w) lookahead instead.
    private val hdrRegex = "\\b(${hdrCanonical.joinToString("|") { it.replace("+", "\\+") }})(?!\\w)".toRegex(RegexOption.IGNORE_CASE)

    // Multi-word patterns use spaces as separators because the parser normalizes dots/underscores to
    // spaces before running regexes, so dot-based separators would never match.
    private val editionPatterns = listOf(
        "unrated", "extended", "limited", "theatrical", "remastered", "redux", "imax",
        "directors cut",
        "director['’]s cut",
        "special edition",
        "anniversary edition",
        "final cut",
    )
    private val editionRegex = "\\b(${editionPatterns.joinToString("|")})\\b".toRegex(RegexOption.IGNORE_CASE)

    // Groups that sign anywhere in the name, not only in a leading tag or a "-GROUP" suffix: "The.Boys.S03E01.LostFilm.TV",
    // "Gate_[01]_[AniLibria_Tv]_[HDTV-Rip_720p]". Read in this spelling, so a library gets one tag per group.
    private val releaseGroupTags = listOf("LostFilm", "AniLibria", "AniLiberty")
    private val releaseGroupRegex = "\\b(${releaseGroupTags.joinToString("|")})\\b".toRegex(RegexOption.IGNORE_CASE)
    private fun knownGroup(text: String): String? =
        releaseGroupRegex.find(text)?.value?.let { found -> releaseGroupTags.first { it.equals(found, ignoreCase = true) } }


    // A comprehensive list of "stop words" to find the end of the title.
    private val otherStopTags = listOf(
        // Codecs & Audio
        "x264", "h264", "x265", "hevc", "xvid", "aac", "ac3", "dts", "dts-hd", "atmos",
        // Other tags that reliably appear after the title
        "repack", "proper", "internal", "remux", "uhd", "bdremux",
        // HDR — these reliably appear after the title; DV/HLG omitted (too short, higher false-positive risk)
        "hdr10", "hdr", "dovi", "sdr",
    )

    // Combine all tags that signal the end of the title into a single regex.
    private val stopWords = (resolutionTags + qualityTags + otherStopTags).joinToString(separator = "|")
    private val stopWordRegex = "\\b($stopWords)\\b".toRegex(RegexOption.IGNORE_CASE)

    // Scene release group closing the name: "x264-YTS", "x264-YTS.MX", "x264-[YTS.MX]", "x265-GalaxyRG[TGx]",
    // and Radarr's "[x264]-GROUP". The hyphen must follow a letter, digit or ']', which rules out " - " in
    // titles and plexify's own " - [1080p]" suffix.
    private val sceneGroupRegex =
        """(?<=[A-Za-z0-9\]])-(?:\[([A-Za-z0-9][A-Za-z0-9.]*)]|([A-Za-z0-9]+(?:\.[A-Z]{2})?)(?:\[[A-Za-z0-9]+])?)$""".toRegex()
    // Words that end a hyphenated tag (WEB-DL, Blu-ray, DTS-HD, DTS-ES, DTS-X, HDTV-Rip), close a name as a
    // tag (x265-10bit, BDRip-ITA) or are no group at all. Not stop words: those would change titles.
    private val notSceneGroups = setOf(
        "dl", "rip", "dlrip", "ray", "hd", "ma", "hdma", "es", "ex", "x", "avc", "h265", "av1", "8bit", "10bit",
        "sample", "trailer", "multi", "dual", "sub", "subs", "dub",
        "eng", "rus", "ukr", "ita", "fre", "french", "ger", "german", "spa", "pol", "jpn", "kor",
    )
    // Usenet reposts append their own word after the group: "x264-SPARKS-Obfuscated", "h264-NTb-postbot"
    private val repostSuffixRegex =
        """-(?:Obfuscated|Scrambled|postbot|xpost|Rakuv\w*|AsRequested|AlternativeToRequested|WhiteRev|BUYMORE|RePACKPOST|sample)$"""
            .toRegex(RegexOption.IGNORE_CASE)
    // A name segment that is a release tag as a whole, hyphen included ("WEB-DL", "Blu-ray", "DTS-HD")
    private val wholeTagRegex = "^($stopWords)$".toRegex(RegexOption.IGNORE_CASE)

    // Regex for cleaning up the final title string
    private val delimiterRegex = "[._\\[\\]()-]+".toRegex()
    private val cleanupRegex = "\\s+".toRegex()


    fun parse(filename: String, parentDirName: String? = null): ParsedMediaInfo {
        val parsed = parseName(filename, parentDirName)
        // Some groups sign only the release folder ("Shuumatsu no Walkure III - AniLiberty [WEB-DL 1080p HEVC]/
        // Shuumatsu_no_Walkure_III_[01]_[HEVC].mkv"). Only a known group counts there: a folder is free text.
        // Normalized like a filename, as "\b" never matches between "_" and a word ("Show_[AniLibria_TV]_[WEBRip]").
        val folderGroup = parentDirName?.takeIf { parsed.releaseGroup == null }
            ?.let { knownGroup(it.replace('.', ' ').replace('_', ' ')) } ?: return parsed
        return when (parsed) {
            is ParsedMediaInfo.Movie -> parsed.copy(releaseGroup = folderGroup)
            is ParsedMediaInfo.Episode -> parsed.copy(releaseGroup = folderGroup)
        }
    }

    private fun parseName(filename: String, parentDirName: String?): ParsedMediaInfo {
        val workingFilename = filename.substringBeforeLast('.').replace(providerIdTagRegex, " ")
        // Normalize once; all tier regexes run against this
        val normalized = workingFilename.replace('.', ' ').replace('_', ' ')
        // A bracketed number leading the name ("[01].mkv") is an episode, not a group
        val leadingGroup = leadingGroupRegex.find(workingFilename)?.groupValues?.get(1)?.trim()
            ?.takeIf { tag -> tag.any { it.isLetter() } && !websiteRegex.containsMatchIn(tag) && !looksLikeTag(tag) }
        val sceneGroup by lazy { sceneGroupOf(workingFilename) }

        // --- Tier 1: Standard SxxExx, then 1x01 ---
        val episodeMatch = episodeRegex.find(workingFilename) ?: crossEpisodeRegex.find(workingFilename)
        if (episodeMatch != null) {
            val showPart = workingFilename.substring(0, episodeMatch.range.first).trimEnd(' ', '-', '.', '_')
            val bracketedYear = trailingYearRegex.find(showPart)
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
            val versionSuffix = versionSuffixRegex.find(afterEpisode)?.value
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
                    versionSuffix == null -> sceneGroupOf(workingFilename, afterBracketOnly = true)
                    else -> versionSuffixGroup(versionSuffix)
                },
            )
        }

        // --- Tier 2a: fansub "[Group] Show - 01 (1080p) [CRC]"; season from the title, else the parent directory ---
        // Dots stay: "Show - 12.5" is a recap special, not episode 12
        val dashText = workingFilename.replace('_', ' ')
        val dashMatch = dashEpisodeRegex.find(dashText)
        if (dashMatch != null) {
            var showPart = dashText.substring(0, dashMatch.range.first).replace(leadingGroupsRegex, "").trim()
            val seasonInTitle = trailingSeasonRegex.find(showPart)
            if (seasonInTitle != null) showPart = showPart.substring(0, seasonInTitle.range.first).trimEnd()
            val showYear = trailingYearRegex.find(showPart)
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

        // --- MOVIE PARSING LOGIC (Fallback) ---
        return parseAsMovie(workingFilename, leadingGroup, sceneGroup)
    }

    // The scene group closing [name] (no extension, delimiters still in place); null unless it is plainly one.
    // Groups sign after the release tags, so a hyphen with no tag before it is a title's ("Spider-Man",
    // "X-MEN"), and one inside the first tag is the tag's own ("Movie.2019.WEB-DL").
    private fun sceneGroupOf(fullName: String, afterBracketOnly: Boolean = false): String? {
        val name = fullName.replace(repostSuffixRegex, "")
        val match = sceneGroupRegex.find(name) ?: return null
        if (afterBracketOnly && name.getOrNull(match.range.first - 1) != ']') return null
        val group = match.groupValues[1].ifEmpty { match.groupValues[2] }
        val firstTag = stopWordRegex.find(name.replace('.', ' ').replace('_', ' ')) ?: return null
        if (match.range.first <= firstTag.range.last) return null
        // A second hyphen in the segment before it: a hyphenated group ("x264-D-Z0N3", "x264-Fight-BB") we
        // would cut in half, unless the segment is a tag itself ("WEB-DL-GROUP")
        val segment = name.substring(0, match.range.first).takeLastWhile { it !in "._ []" }
        if ('-' in segment && !wholeTagRegex.matches(segment)) return null
        // "x264-2020" ends in a year, not a group
        if (group.none { it.isLetter() }) return null
        if (group.substringBefore('.').lowercase() in notSceneGroups) return null
        return group.takeUnless { looksLikeTag(it) }
    }

    // A would-be group holding release metadata rather than a name: "-x264", "-2020", "[1080p HEVC]", "[Extended]"
    private fun looksLikeTag(text: String): Boolean {
        val words = text.replace('.', ' ').replace('_', ' ')
        return stopWordRegex.containsMatchIn(words) || yearRegex.containsMatchIn(words) ||
            hdrRegex.containsMatchIn(words) || editionRegex.containsMatchIn(words)
    }

    // The release group closing plexify's own {version} suffix (" - [1080p] [BluRay] [YTS]"): its last tag, unless
    // that is one of the tags written before the group, as in names without one (" - [1080p] [Directors Cut]").
    private fun versionSuffixGroup(versionSuffix: String): String? {
        val last = bracketTagRegex.findAll(versionSuffix).lastOrNull()?.groupValues?.get(1)?.trim() ?: return null
        val isTag = resolutionRegex.matches(last) || qualityRegex.matches(last) || hdrRegex.matches(last) ||
            editionRegex.matches(last)
        return last.takeUnless { isTag || it.none { char -> char.isLetterOrDigit() } }
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
        rawTitle.replace(leadingGroupsRegex, " ").replace(delimiterRegex, " ").replace(cleanupRegex, " ").trim()

    private fun parseAsMovie(filename: String, leadingGroup: String?, sceneGroup: String?): ParsedMediaInfo {
        // Normalize common delimiters to spaces first, so word boundary matching (\b) is reliable.
        val normalized = filename.replace('.', ' ').replace('_', ' ').replace(cleanupRegex, " ")

        // A leading release group ("[Judas] Suzume (2022)") is not part of the title, unless it is the
        // title itself ("[REC] (2007)").
        val withoutGroups = normalized.replace(leadingGroupsRegex, "")
        val groupless = movieTitleAndYear(withoutGroups).takeIf { withoutGroups != normalized && it.first.isNotEmpty() }
        val (title, year) = groupless ?: movieTitleAndYear(normalized)

        return ParsedMediaInfo.Movie(
            title = title,
            year = year,
            resolution = resolutionRegex.find(normalized)?.value,
            quality = qualityRegex.find(normalized)?.value,
            hdr = extractHdr(normalized),
            releaseGroup = knownGroup(normalized) ?: leadingGroup?.takeIf { groupless != null }
                ?: versionSuffixRegex.find(filename)?.value?.let(::versionSuffixGroup) ?: sceneGroup,
            edition = extractEdition(normalized),
        )
    }

    // The title and year of a movie name, its delimiters already normalized to spaces.
    private fun movieTitleAndYear(name: String): Pair<String, String?> {
        // Our own {version} suffix and Plex's edition tag are metadata only; drop them so tags with no stop
        // word (e.g. "[Final Cut]") don't end up in the title.
        var workingTitle = name.replace(versionSuffixRegex, "").replace(plexEditionTagRegex, " ")
        var year: String? = null
        // A year ends the title, so the release tags come after it: a tag before it is a title word
        // ("Internal.Affairs.1990").
        var titleEndsAtYear = false

        // A year in brackets or parentheses is the most reliable.
        val yearInBracketsMatch = yearInBracketsRegex.findAll(workingTitle).firstOrNull { it.groupValues[1].isReleaseYear() }
        if (yearInBracketsMatch != null) {
            year = yearInBracketsMatch.groupValues[1]
            if (workingTitle.take(yearInBracketsMatch.range.first).any { it.isLetterOrDigit() }) {
                // Whatever follows is not the title: "Avatar (2009) Extended Collector's Edition"
                workingTitle = workingTitle.take(yearInBracketsMatch.range.first)
                titleEndsAtYear = true
            } else {
                // Nothing before it: "(2019) Parasite 1080p"
                workingTitle = workingTitle.replaceRange(yearInBracketsMatch.range, " ")
            }
        } else {
            // Otherwise the last year before the release tags, which start at the first stop word after a
            // year: "2001.A.Space.Odyssey.1968.720p", not the "2020" in "Movie.1999.1080p.x264-2020". A
            // year with no title before it is the title ("1917.1080p"), and so is one no release can have yet.
            val years = yearRegex.findAll(workingTitle).filter { match ->
                match.value.isReleaseYear() && workingTitle.take(match.range.first).any { it.isLetterOrDigit() }
            }.toList()
            val tagsStart = years.firstOrNull()
                ?.let { first -> stopWordRegex.find(workingTitle, first.range.last + 1)?.range?.first }
                ?: workingTitle.length
            val lastYearMatch = years.lastOrNull { it.range.first < tagsStart }
            if (lastYearMatch != null) {
                year = lastYearMatch.value
                workingTitle = workingTitle.take(lastYearMatch.range.first)
                titleEndsAtYear = true
            }
        }

        // A square bracket after the title ends it: a release group or the tag list ("The Witcher Sirens of the
        // Deep [AniLibria] [WEB-DLRip 1080p HEVC]"). One with nothing before it is the title ("[REC] (2007)").
        val bracket = workingTitle.indexOf('[')
        if (bracket > 0 && workingTitle.take(bracket).any { it.isLetterOrDigit() }) {
            workingTitle = workingTitle.take(bracket)
        }

        // With no year to end it, the title ends at the first "stop word".
        if (!titleEndsAtYear) {
            stopWordRegex.find(workingTitle)?.let { workingTitle = workingTitle.take(it.range.first) }
        }

        val cleanTitle = workingTitle.replace(delimiterRegex, " ").replace(cleanupRegex, " ").trim()
        return cleanTitle.lowercase() to year
    }

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
            resolution = resolutionRegex.find(normalized)?.value,
            quality = qualityRegex.find(normalized)?.value,
            hdr = extractHdr(normalized),
            releaseGroup = knownGroup(normalized) ?: leadingGroup ?: sceneGroup,
            edition = extractEdition(editionText),
            bareShowYear = bareShowYear,
            numberedSeason = numberedSeason,
        )

    // The first year outside the show's own title: "1923.S01E01" has none (the show premiered in 2022),
    // "The.Boys.S03E01.2022" has 2022.
    private fun episodeYear(normalized: String, showTitle: String): String? {
        val titleWords = showTitle.split(' ').toSet()
        return yearRegex.findAll(normalized).map { it.value }.firstOrNull { it !in titleWords && it.isReleaseYear() }
    }

    private fun extractHdr(normalizedText: String): String? =
        hdrRegex.find(normalizedText)?.value?.let { hdrLookup[it.lowercase()] }

    private fun extractEdition(normalizedText: String): String? =
        editionRegex.find(normalizedText)?.value?.let { raw ->
            raw.replace(Regex("['']"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .split(" ")
                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        }
}