package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.ParsedMediaInfo
import io.github.hospes.plexify.domain.service.ReleaseTags.isReleaseYear

/** Reads a movie's name: what [MediaFilenameParser] makes of a name that [EpisodeNameParser] doesn't read. */
internal object MovieNameParser {

    private val yearInBracketsRegex = """[\[(](19\d{2}|20\d{2})[\])]""".toRegex()

    /** The movie [filename] names: the file name without its extension and provider-ID tags, delimiters still in place. */
    fun parse(filename: String): ParsedMediaInfo.Movie {
        // Normalize common delimiters to spaces first, so word boundary matching (\b) is reliable.
        val normalized = filename.replace('.', ' ').replace('_', ' ').replace(ReleaseTags.cleanupRegex, " ")

        // A leading release group ("[Judas] Suzume (2022)") is not part of the title, unless it is the
        // title itself ("[REC] (2007)").
        val withoutGroups = normalized.replace(ReleaseGroupReader.leadingGroupsRegex, "")
        val groupless = movieTitleAndYear(withoutGroups).takeIf { withoutGroups != normalized && it.first.isNotEmpty() }
        val (title, year) = groupless ?: movieTitleAndYear(normalized)

        return ParsedMediaInfo.Movie(
            title = title,
            year = year,
            resolution = ReleaseTags.resolutionIn(normalized),
            quality = ReleaseTags.qualityIn(normalized),
            hdr = ReleaseTags.hdrIn(normalized),
            releaseGroup = ReleaseGroupReader.knownGroup(normalized)
                ?: ReleaseGroupReader.leadingGroup(filename)?.takeIf { groupless != null }
                ?: ReleaseTags.versionSuffixRegex.find(filename)?.value?.let(ReleaseGroupReader::versionSuffixGroup)
                ?: ReleaseGroupReader.sceneGroupOf(filename),
            edition = ReleaseTags.editionIn(normalized),
        )
    }

    // The title and year of a movie name, its delimiters already normalized to spaces.
    private fun movieTitleAndYear(name: String): Pair<String, String?> {
        // Our own {version} suffix and Plex's edition tag are metadata only; drop them so tags with no stop
        // word (e.g. "[Final Cut]") don't end up in the title.
        var workingTitle = name.replace(ReleaseTags.versionSuffixRegex, "").replace(ReleaseTags.plexEditionTagRegex, " ")
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
            val years = ReleaseTags.yearRegex.findAll(workingTitle).filter { match ->
                match.value.isReleaseYear() && workingTitle.take(match.range.first).any { it.isLetterOrDigit() }
            }.toList()
            val tagsStart = years.firstOrNull()
                ?.let { first -> ReleaseTags.stopWordRegex.find(workingTitle, first.range.last + 1)?.range?.first }
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
            ReleaseTags.stopWordRegex.find(workingTitle)?.let { workingTitle = workingTitle.take(it.range.first) }
        }

        val cleanTitle = workingTitle.replace(ReleaseTags.delimiterRegex, " ").replace(ReleaseTags.cleanupRegex, " ").trim()
        return cleanTitle.lowercase() to year
    }
}
