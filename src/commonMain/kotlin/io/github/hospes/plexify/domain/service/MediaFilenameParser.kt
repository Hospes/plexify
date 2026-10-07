package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.ParsedMediaInfo

object MediaFilenameParser {

    // --- Regex for TV Show Episode Extraction ---
    // Tier 1: Captures S01E01, s01e01, S1E1, etc.
    private val episodeRegex = """[._\-\s\[(]([Ss](\d{1,2})[Ee](\d{1,2}))(?:[._\-\s\])]|$)""".toRegex()

    // Tier 2: season marker in the (normalized) filename: "Season 2", or a bare "S2" token as in "Gate_S2_[01]"
    private val seasonKeywordRegex = """(?:^|\s)(?:Season\s+|S)(\d{1,2})(?:\s|$)""".toRegex(RegexOption.IGNORE_CASE)

    // Tiers 2-4: [NN] bracket episode — 1-3 digits only (avoids matching [1080p] which contains letters).
    // AniLibria marks a season's last episode "[26_END]", which reads "[26 END]" once normalized.
    private val bracketEpisodeRegex = """[\[(](\d{1,3})(?:\s+END)?[\])]""".toRegex(RegexOption.IGNORE_CASE)

    // Tier 3: Season number from parent directory name ("Season 2", "S02", "S2")
    private val seasonFromDirRegex = """(?:Season|S)\s*(\d{1,2})(?:\b|${'$'})""".toRegex(RegexOption.IGNORE_CASE)

    // --- Regex for Year Extraction ---
    private val yearInBracketsRegex = """[\[(](19\d{2}|20\d{2})[\])]""".toRegex()
    private val yearRegex = """\b(19\d{2}|20\d{2})\b""".toRegex()
    // "Show (2015)": a parenthesized year closing the show title, as in plexify's own episode names
    private val trailingYearRegex = """\((19\d{2}|20\d{2})\)$""".toRegex()

    // --- Names plexify itself writes ---
    // Provider-ID tags: the [tmdbid-27205] / [imdbid-tt1375666] forms our templates produce, plus the
    // {tmdb-27205} / [tmdbid=27205] forms Plex and Jellyfin also read. Never part of the title.
    private val providerIdTagRegex = """[\[{](?:tmdb|imdb|tvdb)(?:id)?[-=][^\]}]*[\]}]""".toRegex(RegexOption.IGNORE_CASE)
    // The {version} suffix PathFormatter appends: " - [1080p] [BluRay] [Extended]"
    private val versionSuffixRegex = """\s-\s(?:\[[^\[\]]+]\s*)+$""".toRegex()

    // --- Specific information to extract ---
    private val resolutionTags = listOf("480p", "720p", "1080p", "2160p", "4k")
    private val resolutionRegex = "\\b(${resolutionTags.joinToString("|")})\\b".toRegex(RegexOption.IGNORE_CASE)

    private val qualityTags = listOf(
        "bluray", "blu-ray", "bdrip", "brrip", "dvd", "dvdrip",
        "web-dl", "web-rip", "webrip", "hdtv", "hdrip", "web-dlrip",
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

    private val releaseGroupTags = listOf("LostFilm")
    private val releaseGroupRegex = "\\b(${releaseGroupTags.joinToString("|")})\\b".toRegex(RegexOption.IGNORE_CASE)


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

    // Regex for cleaning up the final title string
    private val delimiterRegex = "[._\\[\\]()-]+".toRegex()
    private val cleanupRegex = "\\s+".toRegex()


    fun parse(filename: String, parentDirName: String? = null): ParsedMediaInfo {
        val workingFilename = filename.substringBeforeLast('.').replace(providerIdTagRegex, " ")
        // Normalize once; all tier regexes run against this
        val normalized = workingFilename.replace('.', ' ').replace('_', ' ')

        // --- Tier 1: Standard SxxExx ---
        val episodeMatch = episodeRegex.find(workingFilename)
        if (episodeMatch != null) {
            val showPart = workingFilename.substringBefore(episodeMatch.value).trimEnd(' ', '-', '.', '_')
            val showYear = trailingYearRegex.find(showPart)
            val showTitle = (showYear?.let { showPart.substring(0, it.range.first) } ?: showPart)
                .replace(delimiterRegex, " ").replace(cleanupRegex, " ").trim()

            // Plexify's own names: "Show (2015) - S01E13 - Episode Title - [720p] [HDTV]". The episode
            // title is free text, so read metadata only from the show part and the {version} suffix.
            val afterEpisode = workingFilename.substring(episodeMatch.range.last + 1)
            val metadataText = if (afterEpisode.trimStart().startsWith("- ")) {
                showPart + (versionSuffixRegex.find(afterEpisode)?.value ?: "")
            } else {
                workingFilename
            }

            return buildEpisode(
                showTitle = showTitle,
                season = episodeMatch.groupValues[2].toInt(),
                episode = episodeMatch.groupValues[3].toInt(),
                normalized = metadataText.replace('.', ' ').replace('_', ' '),
                year = showYear?.groupValues?.get(1),
            )
        }

        // --- Tier 2: "Season N" / "SN" marker in filename + [NN] bracket episode ---
        val seasonKeywordMatch = seasonKeywordRegex.find(normalized)
        if (seasonKeywordMatch != null) {
            val season = seasonKeywordMatch.groupValues[1].toInt()
            val afterSeason = normalized.substring(seasonKeywordMatch.range.last + 1)
            val bracketEpMatch = bracketEpisodeRegex.find(afterSeason)
            if (bracketEpMatch != null) {
                val showTitle = normalized.substring(0, seasonKeywordMatch.range.first)
                    .replace(delimiterRegex, " ").replace(cleanupRegex, " ").trim()
                return buildEpisode(
                    showTitle = showTitle,
                    season = season,
                    episode = bracketEpMatch.groupValues[1].toInt(),
                    normalized = normalized,
                )
            }
        }

        // --- Tiers 3 & 4: bracket episode [NN] ---
        val bracketEpMatch = bracketEpisodeRegex.find(normalized)
        if (bracketEpMatch != null) {
            val episode = bracketEpMatch.groupValues[1].toInt()
            val rawTitle = normalized.substring(0, bracketEpMatch.range.first)
            val showTitle = rawTitle.replace(delimiterRegex, " ").replace(cleanupRegex, " ").trim()

            // Tier 3: season from parent directory name
            val seasonFromDir = parentDirName
                ?.let { seasonFromDirRegex.find(it) }
                ?.groupValues?.get(1)?.toIntOrNull()

            return buildEpisode(
                showTitle = showTitle,
                season = seasonFromDir,   // null when no parent-dir season found (Tier 4)
                episode = episode,
                normalized = normalized,
            )
        }

        // --- MOVIE PARSING LOGIC (Fallback) ---
        return parseAsMovie(workingFilename)
    }

    private fun parseAsMovie(filename: String): ParsedMediaInfo {
        var workingTitle = filename
        var year: String? = null

        // *** FIX: Normalize common delimiters to spaces BEFORE parsing. ***
        // This makes word boundary matching (\b) reliable for all subsequent regexes.
        workingTitle = workingTitle.replace('.', ' ')
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ") // Collapse multiple spaces

        // 2. Extract optional metadata from the full filename first.
        val resolution = resolutionRegex.find(workingTitle)?.value
        val quality = qualityRegex.find(workingTitle)?.value
        val hdr = extractHdr(workingTitle)
        val releaseGroup = releaseGroupRegex.find(workingTitle)?.value
        val edition = extractEdition(workingTitle)

        // Our own {version} suffix is metadata only; drop it so tags with no stop word (e.g. "[Final Cut]")
        // don't end up in the title.
        workingTitle = workingTitle.replace(versionSuffixRegex, "")

        // 3. Find the year using a prioritized approach.
        val yearInBracketsMatch = yearInBracketsRegex.find(workingTitle)
        if (yearInBracketsMatch != null) {
            // Priority 1: Year in brackets or parentheses is most reliable.
            year = yearInBracketsMatch.groupValues[1] // groupValues[1] is the captured year number
            // Remove the entire bracketed year from the string to not interfere with title parsing.
            workingTitle = workingTitle.replace(yearInBracketsMatch.value, " ")
        } else {
            // Priority 2 (Fallback): Find all potential years and assume the *last* one is the release year.
            // This helps with titles like "2001 A Space Odyssey 1968".
            val yearMatches = yearRegex.findAll(workingTitle).toList()
            if (yearMatches.isNotEmpty()) {
                val lastYearMatch = yearMatches.last()
                year = lastYearMatch.value
                // Cut the string at the position of the last year found. This is our primary title delimiter.
                workingTitle = workingTitle.take(lastYearMatch.range.first)
            }
        }

        // 4. If there's still noise after the title (e.g., no year was found to delimit it),
        //    find the *first* "stop word" and cut the string there.
        val stopWordMatch = stopWordRegex.find(workingTitle)
        if (stopWordMatch != null) {
            workingTitle = workingTitle.take(stopWordMatch.range.first)
        }

        // 5. Sanitize the final title string.
        //    - Replace all common delimiters with a single space.
        //    - Collapse multiple spaces into one.
        //    - Trim whitespace from the start and end.
        val cleanTitle = workingTitle.replace(delimiterRegex, " ").replace(cleanupRegex, " ").trim()

        return ParsedMediaInfo.Movie(
            title = cleanTitle.lowercase(),
            year = year,
            resolution = resolution,
            quality = quality,
            hdr = hdr,
            releaseGroup = releaseGroup,
            edition = edition,
        )
    }

    // Metadata extractors must run on the normalized text: '_' is a word character, so `\b` never
    // matches between '_' and a tag like "720p" in underscore-separated names.
    private fun buildEpisode(
        showTitle: String,
        season: Int?,
        episode: Int,
        normalized: String,
        year: String? = null,
    ) =
        ParsedMediaInfo.Episode(
            showTitle = showTitle.lowercase(),
            season = season,
            episode = episode,
            year = year ?: yearRegex.find(normalized)?.value,
            resolution = resolutionRegex.find(normalized)?.value,
            quality = qualityRegex.find(normalized)?.value,
            hdr = extractHdr(normalized),
            releaseGroup = releaseGroupRegex.find(normalized)?.value,
            edition = extractEdition(normalized),
        )

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