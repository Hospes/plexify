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

    /**
     * [text] with every credential masked: this run's key and token wherever they appear, plus
     * any `api_key=` query value or Bearer token. Ktor's curl engine puts the full request URL,
     * query included, into connection errors, and users paste those into GitHub issues.
     */
    fun redact(text: String): String {
        var redacted = text
        listOfNotNull(apiKey, accessToken).filter { it.isNotEmpty() }.forEach { redacted = redacted.replace(it, MASK) }
        redacted = API_KEY_PARAM.replace(redacted) { it.groupValues[1] + MASK }
        return BEARER.replace(redacted) { it.groupValues[1] + MASK }
    }

    /**
     * [error] itself when no message in its cause chain carries a credential, so callers can still
     * tell its type; otherwise the chain retold as [TmdbRedactedException]s with masked messages.
     */
    fun redact(error: Throwable): Throwable {
        val leaks = generateSequence(error) { it.cause }.any { e -> e.message?.let { redact(it) != it } == true }
        return if (leaks) redactChain(error) else error
    }

    private fun redactChain(error: Throwable): Throwable = TmdbRedactedException(
        originalType = error::class.simpleName,
        message = error.message?.let(::redact),
        cause = error.cause?.let(::redactChain),
    )

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

        private fun String?.mask(): String = if (this == null) "none" else MASK

        private const val MASK = "***"
        private val API_KEY_PARAM = Regex("""(api_key=)[^&\s'"#]+""", RegexOption.IGNORE_CASE)
        private val BEARER = Regex("""(Bearer\s+)[^\s'",]+""", RegexOption.IGNORE_CASE)
    }
}

/** TMDB answered 401: the credentials are wrong, revoked or expired. */
class TmdbCredentialsRejectedException(val source: TmdbCredentials.Source) :
    Exception("TMDB rejected the ${if (source == TmdbCredentials.Source.USER) "supplied" else "built-in"} credentials (HTTP 401)")

/**
 * A TMDB failure retold with the credentials masked (see [TmdbCredentials.redact]). [toString], which
 * stack traces print, keeps the original exception's type.
 */
class TmdbRedactedException(
    private val originalType: String?,
    message: String?,
    cause: Throwable?,
) : Exception(message, cause) {
    override fun toString(): String = listOfNotNull(originalType ?: "Exception", message).joinToString(": ")
}
