package io.github.hospes.plexify.domain.service

import io.github.hospes.plexify.domain.model.ParsedMediaInfo

/**
 * Reads a media file's path: [EpisodeNameParser] reads an episode's name, [MovieNameParser] any other, and
 * [ReleaseGroupReader] and [PathIdReader] add what the folders name. [ReleaseTags] is the vocabulary they share.
 */
object MediaFilenameParser {

    /**
     * Reads a media file's name. [parentDirName] is the name of its folder, and [grandparentDirName] of the folder
     * above, where a library keeps the show's ID tag when the file is in a season folder.
     */
    fun parse(filename: String, parentDirName: String? = null, grandparentDirName: String? = null): ParsedMediaInfo {
        val workingFilename = PathIdReader.withoutIdTags(filename.substringBeforeLast('.'))
        val parsed = EpisodeNameParser.parse(workingFilename, parentDirName) ?: MovieNameParser.parse(workingFilename)
        // Some groups sign only the release folder ("Shuumatsu no Walkure III - AniLiberty [WEB-DL 1080p HEVC]/
        // Shuumatsu_no_Walkure_III_[01]_[HEVC].mkv"). Only a known group counts there: a folder is free text.
        val folderGroup = parentDirName?.takeIf { parsed.releaseGroup == null }?.let(ReleaseGroupReader::folderGroup)
        val mediaId = PathIdReader.mediaIdOf(filename, parsed, parentDirName, grandparentDirName)
        return when (parsed) {
            is ParsedMediaInfo.Movie -> parsed.copy(releaseGroup = parsed.releaseGroup ?: folderGroup, mediaId = mediaId)
            is ParsedMediaInfo.Episode -> parsed.copy(releaseGroup = parsed.releaseGroup ?: folderGroup, mediaId = mediaId)
        }
    }
}
