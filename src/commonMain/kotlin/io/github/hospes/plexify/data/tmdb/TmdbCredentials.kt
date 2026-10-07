package io.github.hospes.plexify.data.tmdb

/**
 * Credentials for the TMDB API and where they came from. Either value authenticates;
 * the read access token is preferred when both are present.
 */
class TmdbCredentials(
    val apiKey: String?,
    val accessToken: String?,
    val source: Source,
) {
    enum class Source {
        /** Passed by the user with a flag or an environment variable. */
        USER,

        /** Baked into the binary at build time; shared by every user of a release. */
        BUILT_IN,
    }

    private val isPresent: Boolean get() = apiKey != null || accessToken != null

    // Never print the secrets themselves.
    override fun toString(): String =
        "TmdbCredentials(source=$source, apiKey=${apiKey.mask()}, accessToken=${accessToken.mask()})"

    companion object {
        /**
         * The user's credentials win as a set: a user key is never mixed with the built-in
         * token, so a wrong user key fails as the user's own problem instead of half-working
         * on the shared credentials. Returns null when neither source has anything.
         */
        fun resolve(
            userApiKey: String?,
            userAccessToken: String?,
            builtInApiKey: String,
            builtInAccessToken: String,
        ): TmdbCredentials? {
            val user = TmdbCredentials(userApiKey.nonBlank(), userAccessToken.nonBlank(), Source.USER)
            if (user.isPresent) return user
            return TmdbCredentials(builtInApiKey.nonBlank(), builtInAccessToken.nonBlank(), Source.BUILT_IN)
                .takeIf { it.isPresent }
        }

        private fun String?.nonBlank(): String? = this?.trim()?.ifEmpty { null }

        private fun String?.mask(): String = if (this == null) "none" else "***"
    }
}

/** TMDB answered 401: the credentials are wrong, revoked or expired. */
class TmdbCredentialsRejectedException(val source: TmdbCredentials.Source) :
    Exception("TMDB rejected the ${if (source == TmdbCredentials.Source.USER) "supplied" else "built-in"} credentials (HTTP 401)")
