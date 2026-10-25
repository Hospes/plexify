package io.github.hospes.plexify.data.tmdb

import io.github.hospes.plexify.data.MetadataConnectionException
import io.github.hospes.plexify.data.MetadataNotFoundException
import io.github.hospes.plexify.data.MetadataTimeoutException
import io.github.hospes.plexify.data.MetadataUnreachableException
import io.github.hospes.plexify.data.nonstrict
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/**
 * TMDB over HTTP for [TmdbProvider]: the client (auth, retries, timeouts), a breaker for a TMDB that stopped
 * answering, and the mapping of every failure to a short message without credentials.
 */
internal class TmdbHttp(
    private val credentials: TmdbCredentials,
    private val timeouts: TmdbTimeouts,
    private val engineFactory: () -> HttpClientEngine,
) {
    private val httpClient by lazy {
        HttpClient(engineFactory()) {
            install(ContentNegotiation) { json(nonstrict) }

            // TMDB rate-limits bursts with 429 (and a Retry-After header). A built-in key is
            // shared by every user of a release, so give a busy moment a few tries.
            install(HttpRequestRetry) {
                retryIf(maxRetries = 3) { _, response -> response.status == HttpStatusCode.TooManyRequests }
                // Fewer tries for a timeout (they share the count above): a stall usually means TMDB
                // or the network is down, and every try costs the full timeout.
                retryOnExceptionIf { request, cause ->
                    cause.isTimeout() && retryCount <= timeouts.retries && OptionalLookup !in request.attributes
                }
                exponentialDelay()
                delay(timeouts.retryDelay)
            }

            // Installed after HttpRequestRetry, so each try gets its own timeouts. The curl engine sets
            // the connect timeout on the handle (DNS included) but ignores a socket timeout; the request
            // timeout cancels the call from Ktor's side, body download included, so it is what catches
            // a server that stops sending.
            install(HttpTimeout) {
                connectTimeoutMillis = timeouts.connectMillis
                requestTimeoutMillis = timeouts.requestMillis
            }

            // The read access token goes in the header up front; the v3 API key only when
            // there is no token. Either one alone authenticates every v3 endpoint.
            defaultRequest {
                url {
                    takeFrom("https://api.themoviedb.org/3/")
                    if (credentials.accessToken == null) credentials.apiKey?.let { parameters.append("api_key", it) }
                }
                credentials.accessToken?.let { headers.append(HttpHeaders.Authorization, "Bearer $it") }
                headers.appendIfNameAbsent(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
        }
    }


    // Requests in a row that got no answer (timed out or could not connect). Files are processed one
    // after another, so in an outage each would wait out the timeouts again: from `unreachableAfter`
    // on, every request fails at once for the rest of the run.
    private var unanswered = 0
    private val unreachable get() = unanswered >= timeouts.unreachableAfter

    // Every result leaves through here, so no caller can print a credential from an error message.
    // A cancelled lookup is not a failed one: cancellation goes on to the caller, unless it is
    // Ktor's own timeout in disguise.
    inline fun <T> catching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: Throwable) {
            if (e is CancellationException && !e.isTimeout()) throw e
            Result.failure(redacted(e))
        }

    fun redacted(e: Throwable): Throwable = credentials.redact(e.unreadableAsFailed())

    /**
     * GET [path] and check the status. A timeout or a connection error fails with a short message
     * naming the [action] instead of Ktor's, which spells out the whole request URL. A failed search
     * is reported on every file it fails, so the message has to stay readable.
     */
    suspend fun get(path: String, block: HttpRequestBuilder.() -> Unit = {}, action: () -> String): HttpResponse {
        if (unreachable) throw MetadataUnreachableException("Not ${action()}: TMDB stopped answering earlier in this run")
        val response = try {
            httpClient.get(path, block)
        } catch (e: Throwable) {
            val failure = when {
                e.isTimeout() -> MetadataTimeoutException("TMDB request timed out ${action()}")
                e is CancellationException -> throw e
                else -> MetadataConnectionException("TMDB request failed ${action()}: ${e.failureReason()}")
            }
            unanswered++
            if (unreachable) {
                throw MetadataUnreachableException(
                    "${failure.message}; TMDB stopped answering ($unanswered requests in a row), so later lookups in this run fail at once"
                )
            }
            throw failure
        }
        unanswered = 0
        response.ensureSuccess(action)
        return response
    }

    /**
     * GET [path] as [get] does and read its JSON body. A body Ktor can't read (no JSON content type, as curl hands out
     * after a timed-out request) fails as the lookup [action] names, not as an UnsupportedOperationException, which
     * callers would take for "TMDB can't do this" (and an IMDb lookup for "no IMDb ID").
     */
    suspend inline fun <reified T> getJson(
        path: String,
        noinline block: HttpRequestBuilder.() -> Unit = {},
        noinline action: () -> String,
    ): T {
        val response = get(path, block, action)
        return try {
            response.body<T>()
        } catch (e: NoTransformationFoundException) {
            throw IllegalStateException("TMDB sent a response that could not be read ${action()}", e)
        }
    }

    private fun HttpResponse.ensureSuccess(action: () -> String) {
        when {
            status.isSuccess() -> Unit
            status == HttpStatusCode.Unauthorized -> throw TmdbCredentialsRejectedException(credentials.source)
            status == HttpStatusCode.NotFound -> throw MetadataNotFoundException("HTTP 404 ${action()}")
            status == HttpStatusCode.TooManyRequests && credentials.source == TmdbCredentials.Source.BUILT_IN ->
                error("TMDB rate limit reached (HTTP 429) ${action()}. The built-in key is shared by all Plexify users; set your own with TMDB_API_ACCESS_TOKEN (see README).")

            else -> error("HTTP ${status.value} ${action()}")
        }
    }
}

/**
 * Limits for one TMDB call. Files are processed one after another, so without them a stalled
 * connection would block the whole run.
 */
data class TmdbTimeouts(
    val connectMillis: Long = 10_000,
    val requestMillis: Long = 30_000,
    /** Extra tries after a timeout, out of the three retries a call gets (HTTP 429 may use all three). */
    val retries: Int = 1,
    /** Request timeout of an optional lookup (alternative titles), which gets no retry after a timeout. */
    val optionalRequestMillis: Long = 10_000,
    /** Requests in a row with no answer (timed out or could not connect) after which TMDB counts as unreachable for the run. */
    val unreachableAfter: Int = 3,
    /**
     * Waits out the back-off before a retry: Ktor's exponential delay (1 s, 2 s, 4 s, each plus up to 1 s
     * at random, or a longer Retry-After). Tests pass one that returns at once.
     */
    val retryDelay: suspend (millis: Long) -> Unit = { delay(it) },
)

// The curl engine's message is "Connection failed for request: <request, URL included>. Reason: <curl error>".
private fun Throwable.failureReason(): String {
    val message = message ?: return this::class.simpleName ?: "unknown error"
    return message.substringAfter("Reason: ", missingDelimiterValue = message)
}

// Marks a request whose failure only costs a detail, so a timeout is not worth a second try.
internal val OptionalLookup = AttributeKey<Unit>("PlexifyOptionalLookup")

internal fun Throwable.isNoAnswer(): Boolean =
    this is MetadataTimeoutException || this is MetadataConnectionException || this is MetadataUnreachableException

// Backs getJson up for a body read some other way: Ktor's NoTransformationFoundException is an
// UnsupportedOperationException, which callers would take for "TMDB can't do this" instead of a failed lookup.
private fun Throwable.unreadableAsFailed(): Throwable =
    if (this is NoTransformationFoundException) IllegalStateException("TMDB sent a response that could not be read", this) else this

// Ktor may deliver a timeout wrapped in a CancellationException.
internal fun Throwable.isTimeout(): Boolean = generateSequence(this) { it.cause }.take(8).any {
    it is HttpRequestTimeoutException || it is ConnectTimeoutException || it is SocketTimeoutException
}
