package io.github.hospes.plexify.domain.service

data class ParsedMovieInfo(val title: String, val year: String?)

object MovieFilenameParser {

    // Regex to find and extract the year. It's the strongest signal.
    private val yearRegex = """\b(19\d{2}|20\d{2})\b""".toRegex()

    // A comprehensive list of "stop words". The title is everything that comes BEFORE the first of these.
    // We don't need to match them all, just the first one that appears after the title.
    private val stopWords = listOf(
        // Resolutions
        "480p", "720p", "1080p", "2160p", "4k",
        // Sources
        "bluray", "blu-ray", "bdrip", "brrip", "dvd", "dvdrip", "web-dl", "web-rip", "webrip", "hdtv", "hdrip",
        // Codecs & Audio
        "x264", "h264", "x265", "hevc", "xvid", "aac", "ac3", "dts", "dts-hd", "atmos",
        // Other tags that reliably appear after the title
        "repack", "proper", "unrated", "extended", "directors.cut", "limited", "internal", "remux"
    ).joinToString(separator = "|")

    // A single, powerful regex to find the first occurrence of any stop word.
    // We use word boundaries (\b) to avoid matching parts of titles (e.g., 'rip' in 'Serenity').
    private val stopWordRegex = "\\b($stopWords)\\b".toRegex(RegexOption.IGNORE_CASE)

    // Regex for cleaning up the final title string
    private val delimiterRegex = "[._\\[\\]()-]+".toRegex()
    private val cleanupRegex = "\\s+".toRegex()


    fun parse(filename: String): ParsedMovieInfo {
        // 1. Remove the file extension first. This is always noise.
        var workingTitle = filename.substringBeforeLast('.')

        // 2. Find the year. It's our primary delimiter for the title.
        val yearMatch = yearRegex.find(workingTitle)
        val year = yearMatch?.value

        // 3. If a year is found, we consider everything before it as the potential title.
        //    This is the most reliable way to separate title from metadata.
        if (yearMatch != null) {
            workingTitle = workingTitle.take(yearMatch.range.first)
        }

        // 4. In case there was no year, or there's still noise before the year,
        //    we find the *first* "stop word" and cut the string there.
        //    This handles filenames like "Superman.1080p.mkv" (no year).
        val stopWordMatch = stopWordRegex.find(workingTitle)
        if (stopWordMatch != null) {
            // Cut off everything from the stop word onwards
            workingTitle = workingTitle.take(stopWordMatch.range.first)
        }

        // 5. Sanitize the final title string.
        //    - Replace all common delimiters with a single space.
        //    - Collapse multiple spaces into one.
        //    - Trim whitespace from the start and end.
        val cleanTitle = workingTitle.replace(delimiterRegex, " ").replace(cleanupRegex, " ").trim()

        return ParsedMovieInfo(title = cleanTitle.lowercase(), year = year)
    }
}