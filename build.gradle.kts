plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "io.github.hospes"
version = "1.0-SNAPSHOT"

kotlin {
    linuxX64("linux") {
        binaries {
            executable {
                entryPoint = "io.github.hospes.plexify"
                baseName = "plexify"
            }
        }
    }
    mingwX64("windows") {
        binaries {
            executable {
                entryPoint = "io.github.hospes.plexify"
                baseName = "plexify"
            }
        }
    }
}