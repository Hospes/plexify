package io.github.hospes.plexify.data

import io.github.hospes.plexify.domain.model.MediaSearchResult

interface MetadataProvider {
    suspend fun search(title: String, year: String?): Result<List<MediaSearchResult>>
}