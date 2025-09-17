package io.github.hospes.plexify.web

import kotlinx.serialization.json.Json

val nonstrict = Json {
    encodeDefaults = true
    isLenient = true
    ignoreUnknownKeys = true
    allowSpecialFloatingPointValues = true
    useArrayPolymorphism = true
}