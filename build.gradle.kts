import org.jetbrains.kotlin.konan.properties.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.build.config)
}

group = "io.github.hospes"
version = "1.0-SNAPSHOT"

buildConfig {
    packageName("io.github.hospes.plexify")

    val localProperties = loadLocalProperties()
    buildConfigField<String>(
        name = "TMDB_API_KEY",
        value = provider<String> {
            System.getenv("TMDB_API_KEY") ?: properties["TMDB_API_KEY"]?.toString()
            ?: localProperties["TMDB_API_KEY"]?.toString() ?: ""
        },
    )
    buildConfigField<String>(
        name = "TMDB_API_READ_ACCESS_TOKEN",
        value = provider {
            System.getenv("TMDB_API_READ_ACCESS_TOKEN") ?: properties["TMDB_API_READ_ACCESS_TOKEN"]?.toString()
            ?: localProperties["TMDB_API_READ_ACCESS_TOKEN"]?.toString() ?: ""
        },
    )
    buildConfigField<String>(
        name = "TVDB_API_KEY",
        value = provider<String> {
            System.getenv("TVDB_API_KEY") ?: properties["TVDB_API_KEY"]?.toString()
            ?: localProperties["TVDB_API_KEY"]?.toString() ?: ""
        },
    )
    buildConfigField<String>(
        name = "OMDB_API_KEY",
        value = provider<String> {
            System.getenv("OMDB_API_KEY") ?: properties["OMDB_API_KEY"]?.toString()
            ?: localProperties["OMDB_API_KEY"]?.toString() ?: ""
        },
    )
}

kotlin {
    linuxX64("linux") {
        binaries {
            executable {
                entryPoint = "io.github.hospes.plexify.main"
                baseName = "plexify"
            }
        }
    }
    mingwX64("windows") {
        binaries {
            executable {
                entryPoint = "io.github.hospes.plexify.main"
                baseName = "plexify"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.clikt)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.ktor.core)
            implementation(libs.ktor.auth)
            implementation(libs.ktor.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }

        linuxMain.dependencies {
            implementation(libs.ktor.curl)
        }

        val windowsMain by getting {
            dependencies {
                implementation(libs.ktor.curl)
            }
        }
    }

    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

fun loadLocalProperties(): Properties {
    val properties = Properties()
    val localPropertiesFile = File("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { inputStream ->
            properties.load(inputStream)
        }
    }
    return properties
}