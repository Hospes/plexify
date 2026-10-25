package io.github.hospes.plexify.domain.service

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * The release tags a media file name carries (resolution, quality, HDR, edition), the stop words that end its title,
 * its years, and the delimiters between them: the vocabulary [ReleaseGroupReader], [MovieNameParser] and
 * [EpisodeNameParser] share.
 */
internal object ReleaseTags {

    // --- Regex for Year Extraction ---
    val yearRegex = """\b(19\d{2}|20\d{2})\b""".toRegex()
    // "Show (2015)": a parenthesized year closing the show title, as in plexify's own episode names
    val trailingYearRegex = """\((19\d{2}|20\d{2})\)$""".toRegex()

    // The latest year a release can carry: next year's films already circulate as festival copies and
    // screeners. A later one is a title word, as in "Blade.Runner.2049.1080p".
    private val latestReleaseYear = Clock.System.now().toLocalDateTime(TimeZone.UTC).year + 1
    fun String.isReleaseYear(): Boolean = toInt() <= latestReleaseYear

    // --- Tags plexify itself writes ---
    // Plex's edition tag: "Blade Runner (1982) {edition-Final Cut}"
    val plexEditionTagRegex = """\{edition-[^}]*}""".toRegex(RegexOption.IGNORE_CASE)
    // The {version} suffix PathFormatter appends: " - [1080p] [BluRay] [Extended]"
    val versionSuffixRegex = """\s-\s(?:\[[^\[\]]+]\s*)+$""".toRegex()

    // --- Specific information to extract ---
    private val resolutionTags = listOf("480p", "720p", "1080p", "2160p", "4k")
    val resolutionRegex = "\\b(${resolutionTags.joinToString("|")})\\b".toRegex(RegexOption.IGNORE_CASE)

    private val qualityTags = listOf(
        "bluray", "blu-ray", "bdrip", "brrip", "dvd", "dvdrip",
        "web-dl", "webdl", "web-rip", "webrip", "hdtv", "hdrip", "web-dlrip",
    )
    val qualityRegex = "\\b(${qualityTags.joinToString("|")})\\b".toRegex(RegexOption.IGNORE_CASE)

    // Order matters: more specific variants must come before shorter ones (HDR10+ before HDR10 before HDR).
    private val hdrCanonical = listOf("HDR10+", "HDR10", "HDR", "DV", "DoVi", "HLG", "SDR")
    private val hdrLookup: Map<String, String> = hdrCanonical.associateBy { it.lowercase() }
    // Trailing \b can't follow '+' (non-word char), so use (?!\w) lookahead instead.
    val hdrRegex = "\\b(${hdrCanonical.joinToString("|") { it.replace("+", "\\+") }})(?!\\w)".toRegex(RegexOption.IGNORE_CASE)

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
    val editionRegex = "\\b(${editionPatterns.joinToString("|")})\\b".toRegex(RegexOption.IGNORE_CASE)

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
    val stopWords = (resolutionTags + qualityTags + otherStopTags).joinToString(separator = "|")
    val stopWordRegex = "\\b($stopWords)\\b".toRegex(RegexOption.IGNORE_CASE)

    // Regex for cleaning up the final title string
    val delimiterRegex = "[._\\[\\]()-]+".toRegex()
    val cleanupRegex = "\\s+".toRegex()

    fun resolutionIn(normalizedText: String): String? = resolutionRegex.find(normalizedText)?.value

    fun qualityIn(normalizedText: String): String? = qualityRegex.find(normalizedText)?.value

    fun hdrIn(normalizedText: String): String? =
        hdrRegex.find(normalizedText)?.value?.let { hdrLookup[it.lowercase()] }

    fun editionIn(normalizedText: String): String? =
        editionRegex.find(normalizedText)?.value?.let { raw ->
            raw.replace(Regex("['’]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .split(" ")
                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        }
}
