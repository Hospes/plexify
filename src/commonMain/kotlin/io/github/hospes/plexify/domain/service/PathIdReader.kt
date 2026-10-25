package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.MediaId
import io.github.hospes.plexify.domain.model.ParsedMediaInfo

/**
 * The movie or show ID a path names ([ParsedMediaInfo.mediaId]): an ID tag in the file name or in a folder that names
 * the file's own title, else a title plexify named by its ID.
 */
internal object PathIdReader {

    // --- Names plexify itself writes ---
    // Provider-ID tags: the [tmdbid-27205] / {imdb-tt1375666} forms our templates produce, [imdbid-tt1375666] from
    // libraries made by 0.5.1 or earlier, plus the {tmdb-27205} / [tmdbid=27205] forms Plex and Jellyfin also read.
    // Never part of the title.
    private val providerIdTagRegex = """[\[{](?:tmdb|imdb|tvdb)(?:id)?[-=][^\]}]*[\]}]""".toRegex(RegexOption.IGNORE_CASE)
    // The TMDB or IMDb ID in such a tag, for looking the media up by it. TVDB IDs are not looked up.
    private val tmdbIdTagRegex = """[\[{]tmdb(?:id)?[-=](\d+)[\]}]""".toRegex(RegexOption.IGNORE_CASE)
    private val imdbIdTagRegex = """[\[{]imdb(?:id)?[-=](tt\d+)[\]}]""".toRegex(RegexOption.IGNORE_CASE)
    // A title named by its ID, as PathFormatter names one with nothing usable: "tmdb-1721501 (2026) - [1080p]",
    // "imdb-tt0133093 - S01E01 - Pilot". Exactly that spelling, followed by the year, a tag, " - " or nothing.
    private val idTitleRegex = """^(?:tmdb-(\d+)|imdb-(tt\d+))(?=\s\((?:19|20)\d{2}\)|\s[\[{]|\s-\s|$)""".toRegex()
    // A season folder in a library ("Season 01", "Specials"): the show folder above it may hold the show's ID tag.
    private val seasonFolderRegex = """^(?:Season\s*\d{1,4}|Specials)$""".toRegex(RegexOption.IGNORE_CASE)

    // [name] without its provider-ID tags, which are never part of the title
    fun withoutIdTags(name: String): String = name.replace(providerIdTagRegex, " ")

    /**
     * The ID the path of [filename] names, read as [parsed]. [parentDirName] is the name of its folder, and
     * [grandparentDirName] of the folder above, which is an episode's show folder when the file is in a season folder.
     */
    fun mediaIdOf(filename: String, parsed: ParsedMediaInfo, parentDirName: String?, grandparentDirName: String?): MediaId? {
        val showDirName = grandparentDirName?.takeIf { parsed is ParsedMediaInfo.Episode && parentDirName?.let(seasonFolderRegex::matches) == true }
        val title = when (parsed) {
            is ParsedMediaInfo.Movie -> parsed.title
            is ParsedMediaInfo.Episode -> parsed.showTitle
        }
        val ownFolders = listOfNotNull(parentDirName, showDirName).filter { namesTitle(it, title) }
        return mediaIdIn(filename.substringBeforeLast('.'), ownFolders)
    }

    /**
     * Whether [folderName] is the folder of the movie or show [title] names: the same title once its tags and closing
     * year are dropped, as in plexify's (and Radarr's and Sonarr's) names. A show folder's tag is not its movie's
     * ("Breaking Bad (2008) [tmdbid-1396]/El Camino A Breaking Bad Movie.mkv"), nor a movie folder's its sequel's. A
     * title PathFormatter shortened ("…") only needs to agree up to where the shorter one is cut.
     */
    private fun namesTitle(folderName: String, title: String): Boolean {
        val folderTitle = folderName.replace(providerIdTagRegex, " ").replace(ReleaseTags.plexEditionTagRegex, " ").trim()
            .replace(ReleaseTags.trailingYearRegex, "")
        fun String.comparable() = lowercase().filter { it.isLetterOrDigit() || it == '…' }
        val (folder, file) = folderTitle.comparable() to title.comparable()
        if ('…' !in folder && '…' !in file) return folder.isNotEmpty() && folder == file
        val (shorter, longer) = listOf(folder.substringBefore('…'), file.substringBefore('…')).sortedBy { it.length }
        return shorter.isNotEmpty() && longer.startsWith(shorter)
    }

    /**
     * The ID a path names: an ID tag in the file name, else in its own folders, nearest first (an episode's show folder
     * holds its show's tag), a TMDB tag before an IMDb one; else a file named by its title's ID ("tmdb-1721501 (2026)").
     * When the title is such an ID, so is a tag's ID [MediaId.fromTitle]: there is no title to check the record by.
     */
    private fun mediaIdIn(name: String, folderNames: List<String>): MediaId? {
        val idTitle = idTitleRegex.find(name)
        for (segment in listOf(name) + folderNames) {
            tmdbIdTagRegex.find(segment)?.let { return MediaId(MediaId.Database.TMDB, it.groupValues[1], fromTitle = idTitle != null) }
            imdbIdTagRegex.find(segment)?.let { return MediaId(MediaId.Database.IMDB, it.groupValues[1].lowercase(), fromTitle = idTitle != null) }
        }
        if (idTitle == null) return null
        val (tmdbId, imdbId) = idTitle.destructured
        return if (tmdbId.isNotEmpty()) MediaId(MediaId.Database.TMDB, tmdbId, fromTitle = true)
        else MediaId(MediaId.Database.IMDB, imdbId, fromTitle = true)
    }
}
