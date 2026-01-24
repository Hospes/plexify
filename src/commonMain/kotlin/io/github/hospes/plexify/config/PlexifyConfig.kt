package io.github.hospes.plexify.config

import kotlinx.serialization.Serializable

@Serializable
data class PlexifyConfig(
    val defaultTemplate: String = "Jellyfin",
    val templates: Map<String, String> = emptyMap(),
)
