package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.ParsedMediaInfo

object MediaFilenameParser {

    // --- Regex for TV Show Episode Extraction ---
    // Captures S01E01, s01e01, S1E1, etc.
    private val episodeRegex = """[._\-\s\[(]([Ss](\d{1,2})[Ee](\d{1,2}))(?:[._\-\s\])]|$)""".toRegex()

    // --- Regex for Year Extraction ---
    private val yearInBracketsRegex = """[\[(](19\d{2}|20\d{2})[\])]""".toRegex()
    private val yearRegex = """\b(19\d{2}|20\d{2})\b""".toRegex()

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


    fun parse(filename: String): ParsedMediaInfo {
        val workingFilename = filename.substringBeforeLast('.')

        // --- TV SHOW PARSING LOGIC ---
        val episodeMatch = episodeRegex.find(workingFilename)
        if (episodeMatch != null) {
            val showTitle = workingFilename.substringBefore(episodeMatch.value)
                .replace(delimiterRegex, " ")
                .replace(cleanupRegex, " ").trim()
            val season = episodeMatch.groupValues[2].toInt()
            val episode = episodeMatch.groupValues[3].toInt()
            val year = yearRegex.find(workingFilename)?.value
            // Normalize for multi-word pattern matching (edition/HDR with space separators)
            val normalizedFilename = workingFilename.replace('.', ' ').replace('_', ' ')

            return ParsedMediaInfo.Episode(
                showTitle = showTitle.lowercase(),
                season = season,
                episode = episode,
                year = year,
                resolution = resolutionRegex.find(workingFilename)?.value,
                quality = qualityRegex.find(workingFilename)?.value,
                hdr = extractHdr(normalizedFilename),
                releaseGroup = releaseGroupRegex.find(workingFilename)?.value,
                edition = extractEdition(normalizedFilename),
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