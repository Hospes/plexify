package io.github.hospes.plexify.data

import kotlinx.serialization.json.Json

val nonstrict = Json {
    encodeDefaults = true
    isLenient = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}