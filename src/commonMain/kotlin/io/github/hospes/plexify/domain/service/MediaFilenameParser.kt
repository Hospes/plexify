package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.ParsedMediaInfo

object MediaFilenameParser {

    // --- Regex for TV Show Episode Extraction ---
    // Captures S01E01, s01e01, S1E1, etc.
    private val episodeRegex = """[._\-\s]([Ss](\d{1,2})[Ee](\d{1,2}))[._\-\s]""".toRegex()

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

    private val editionTags = listOf("unrated", "extended", "directors.cut", "limited", "theatrical")
    private val editionRegex = "\\b(${editionTags.joinToString("|").replace(".", "\\.")})\\b".toRegex(RegexOption.IGNORE_CASE)

    private val releaseGroupTags = listOf("LostFilm", "AniLibria")
    private val releaseGroupRegex = "\\b(${releaseGroupTags.joinToString("|")})\\b".toRegex(RegexOption.IGNORE_CASE)


    // A comprehensive list of "stop words" to find the end of the title.
    private val otherStopTags = listOf(
        // Codecs & Audio
        "x264", "h264", "x265", "hevc", "xvid", "aac", "ac3", "dts", "dts-hd", "atmos",
        // Other tags that reliably appear after the title
        "repack", "proper", "internal", "remux", "uhd", "bdremux"
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

            return ParsedMediaInfo.Episode(
                showTitle = showTitle.lowercase(),
                season = season,
                episode = episode,
                year = year,
                resolution = resolutionRegex.find(workingFilename)?.value,
                quality = qualityRegex.find(workingFilename)?.value,
                releaseGroup = releaseGroupRegex.find(workingFilename)?.value
            )
        }

        // --- MOVIE PARSING LOGIC (Fallback) ---
        return parseAsMovie(workingFilename)
    }

    private fun parseAsMovie(filename: String): ParsedMediaInfo {
        // 1. Remove the file extension. This is always noise.
        var workingTitle = filename.substringBeforeLast('.')
        var year: String? = null

        // *** FIX: Normalize common delimiters to spaces BEFORE parsing. ***
        // This makes word boundary matching (\b) reliable for all subsequent regexes.
        workingTitle = workingTitle.replace('_', '.')

        // 2. Extract optional metadata from the full filename first.
        val resolution = resolutionRegex.find(workingTitle)?.value
        val quality = qualityRegex.find(workingTitle)?.value
        val releaseGroup = releaseGroupRegex.find(workingTitle)?.value
        val edition = editionRegex.find(workingTitle)?.value?.replace(".", " ")?.let {
            // Capitalize words for better presentation, e.g., "directors cut" -> "Directors Cut"
            it.split(" ").joinToString(" ") { word -> word.replaceFirstChar { char -> char.uppercase() } }
        }

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
            releaseGroup = releaseGroup,
            edition = edition,
        )
    }
}