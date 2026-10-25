package io.github.hospes.plexify.domain.service

/**
 * The release group a media file name names: a tag leading it ("[SubsPlease] Show - 01"), a scene group closing it
 * ("x264-YTS"), the last tag of plexify's own {version} suffix, or a known group signing anywhere in it or its folder.
 * Which one wins is the name parsers' choice.
 */
internal object ReleaseGroupReader {

    // Release group tags leading the name, as fansubs write them: "[SubsPlease] Show - 01"
    val leadingGroupsRegex = """^(?:\s*\[[^\[\]]*])+""".toRegex()
    private val leadingGroupRegex = """^\s*\[([^\[\]]+)]""".toRegex()
    // A site advertising itself in the leading tag ("[ www.Torrenting.com ] - Show"), not a group
    private val bracketTagRegex = """\[([^\[\]]+)]""".toRegex()
    private val websiteRegex ="""^www\.|\.(?:com|net|org|info|io|to|cc|cd|me|ws)$""".toRegex(RegexOption.IGNORE_CASE)

    // Groups that sign anywhere in the name, not only in a leading tag or a "-GROUP" suffix: "The.Boys.S03E01.LostFilm.TV",
    // "Gate_[01]_[AniLibria_Tv]_[HDTV-Rip_720p]". Read in this spelling, so a library gets one tag per group.
    private val releaseGroupTags = listOf("LostFilm", "AniLibria", "AniLiberty")
    private val releaseGroupRegex = "\\b(${releaseGroupTags.joinToString("|")})\\b".toRegex(RegexOption.IGNORE_CASE)
    fun knownGroup(text: String): String? =
        releaseGroupRegex.find(text)?.value?.let { found -> releaseGroupTags.first { it.equals(found, ignoreCase = true) } }

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

    // The group in the tag leading [name] (no extension, delimiters still in place). A bracketed number leading
    // the name ("[01].mkv") is an episode, not a group.
    fun leadingGroup(name: String): String? =
        leadingGroupRegex.find(name)?.groupValues?.get(1)?.trim()
            ?.takeIf { tag -> tag.any { it.isLetter() } && !websiteRegex.containsMatchIn(tag) && !looksLikeTag(tag) }

    // A known group signing the release folder. Normalized like a filename, as "\b" never matches between "_" and
    // a word ("Show_[AniLibria_TV]_[WEBRip]").
    fun folderGroup(folderName: String): String? = knownGroup(folderName.replace('.', ' ').replace('_', ' '))

    // The scene group closing [name] (no extension, delimiters still in place); null unless it is plainly one.
    // Groups sign after the release tags, so a hyphen with no tag before it is a title's ("Spider-Man",
    // "X-MEN"), and one inside the first tag is the tag's own ("Movie.2019.WEB-DL").
    fun sceneGroupOf(fullName: String, afterBracketOnly: Boolean = false): String? {
        val name = fullName.replace(repostSuffixRegex, "")
        val match = sceneGroupRegex.find(name) ?: return null
        if (afterBracketOnly && name.getOrNull(match.range.first - 1) != ']') return null
        val group = match.groupValues[1].ifEmpty { match.groupValues[2] }
        val firstTag = ReleaseTags.stopWordRegex.find(name.replace('.', ' ').replace('_', ' ')) ?: return null
        if (match.range.first <= firstTag.range.last) return null
        // A second hyphen in the segment before it: a hyphenated group ("x264-D-Z0N3", "x264-Fight-BB") we
        // would cut in half, unless the segment is a tag itself ("WEB-DL-GROUP")
        val segment = name.substring(0, match.range.first).takeLastWhile { it !in "._ []" }
        if ('-' in segment && !ReleaseTags.isWholeTag(segment)) return null
        // "x264-2020" ends in a year, not a group
        if (group.none { it.isLetter() }) return null
        if (group.substringBefore('.').lowercase() in notSceneGroups) return null
        return group.takeUnless { looksLikeTag(it) }
    }

    // A would-be group holding release metadata rather than a name: "-x264", "-2020", "[1080p HEVC]", "[Extended]"
    private fun looksLikeTag(text: String): Boolean {
        val words = text.replace('.', ' ').replace('_', ' ')
        return ReleaseTags.stopWordRegex.containsMatchIn(words) || ReleaseTags.yearRegex.containsMatchIn(words) ||
            ReleaseTags.hdrRegex.containsMatchIn(words) || ReleaseTags.editionRegex.containsMatchIn(words)
    }

    // The release group closing plexify's own {version} suffix (" - [1080p] [BluRay] [YTS]"): its last tag, unless
    // that is one of the tags written before the group, as in names without one (" - [1080p] [Directors Cut]").
    fun versionSuffixGroup(versionSuffix: String): String? {
        val last = bracketTagRegex.findAll(versionSuffix).lastOrNull()?.groupValues?.get(1)?.trim() ?: return null
        val isTag = ReleaseTags.resolutionRegex.matches(last) || ReleaseTags.qualityRegex.matches(last) ||
            ReleaseTags.hdrRegex.matches(last) || ReleaseTags.editionRegex.matches(last)
        return last.takeUnless { isTag || it.none { char -> char.isLetterOrDigit() } }
    }
}
