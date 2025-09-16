plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
}

group = "io.github.hospes"
version = "1.0-SNAPSHOT"

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
}