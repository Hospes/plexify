package io.github.hospes.plexify.data

import io.github.hospes.plexify.data.imdb.dto.ImdbMediaItemDto
import io.github.hospes.plexify.data.tmdb.dto.TmdbMediaItemDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

val nonstrict = Json {
    encodeDefaults = true
    isLenient = true
    ignoreUnknownKeys = true
    coerceInputValues = true

    serializersModule = SerializersModule {
        polymorphic(ImdbMediaItemDto::class) {
            subclass(ImdbMediaItemDto.Movie::class)
            subclass(ImdbMediaItemDto.TvShow::class)
            subclass(ImdbMediaItemDto.TvMiniShow::class)

            defaultDeserializer { ImdbMediaItemDto.Unknown.serializer() }
        }

        polymorphic(TmdbMediaItemDto::class) {
            subclass(TmdbMediaItemDto.Movie::class)
            subclass(TmdbMediaItemDto.TvShow::class)

            defaultDeserializer { TmdbMediaItemDto.Unknown.serializer() }
        }
    }
}