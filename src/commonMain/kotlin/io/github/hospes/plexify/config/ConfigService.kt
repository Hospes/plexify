package io.github.hospes.plexify.config

import com.charleskorn.kaml.Yaml
import io.github.hospes.plexify.core.getHomeDirectory
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

object ConfigService {
    private val configFileName = ".plexify"

    private val defaultConfig = PlexifyConfig(
        defaultTemplate = "Jellyfin",
        templates = mapOf(
            "Jellyfin" to "{CleanTitle} ({year}) [tmdbid-{tmdbid}]/{CleanTitle} ({year}) {version}.{ext}",
            "Plex" to "{CleanTitle} ({year}) [imdbid-{imdbid}]/{CleanTitle} ({year}) {version}.{ext}",
            "Simple" to "{CleanTitle} ({year})/{CleanTitle}.{ext}",
        )
    )

    fun load(): PlexifyConfig {
        val home = getHomeDirectory()
        val configFile = Path(home, configFileName)
        println("Loading configuration from $configFile")

        if (!SystemFileSystem.exists(configFile)) {
            createDefaultConfig(home, configFile)
            return defaultConfig
        }

        return try {
            val content = SystemFileSystem.source(configFile).buffered().use {
                it.readString()
            }
            Yaml.default.decodeFromString(PlexifyConfig.serializer(), content)
        } catch (e: Exception) {
            println("Warning: Failed to parse configuration file at $configFile. Using defaults. Error: ${e.message}")
            defaultConfig
        }
    }

    private fun createDefaultConfig(dir: Path, file: Path) {
        try {
            if (!SystemFileSystem.exists(dir)) {
                SystemFileSystem.createDirectories(dir)
            }
            val yaml = Yaml.default.encodeToString(PlexifyConfig.serializer(), defaultConfig)

            val finalContent = """
                # Plexify Configuration
                # ---------------------
                # This file allows you to define custom naming templates.
                #
                # Placeholders:
                # {Title}, {CleanTitle}, {year}, {season}, {episode}, {ext}, {quality}, {resolution}, {version}
                # {imdbid}, {tmdbid}
                #
                # Conditional Blocks:
                # [imdbid-{imdbid}] -> will only appear if imdbid is present.
                #
            """.trimIndent() + "\n" + yaml

            SystemFileSystem.sink(file).buffered().use {
                it.writeString(finalContent)
            }
            println("Created default configuration at: $file")
        } catch (e: Exception) {
            println("Warning: Could not create default configuration file. ${e.message}")
        }
    }
}
