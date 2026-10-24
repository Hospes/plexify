package io.github.hospes.plexify.data.tmdb

import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** A user's API key. Tests that check an error never shows the key look for "secret-key". */
internal val keyCredentials = TmdbCredentials(apiKey = "secret-key", accessToken = null, source = TmdbCredentials.Source.USER)

/** A user's access token. */
internal val tokenCredentials = TmdbCredentials(apiKey = null, accessToken = "token", source = TmdbCredentials.Source.USER)

private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

/** TMDB's body for an ID or path it doesn't know, sent with HTTP 404. */
internal const val TMDB_NOT_FOUND = """{"success":false,"status_code":34,"status_message":"The resource you requested could not be found."}"""

/** The real [TmdbProvider], with [handler] answering its requests in place of TMDB. */
internal fun mockProvider(
    timeouts: TmdbTimeouts = TmdbTimeouts(),
    credentials: TmdbCredentials = keyCredentials,
    handler: MockRequestHandler,
) = TmdbProvider(credentials, timeouts) { MockEngine(handler) }

/**
 * Runs [block] within [millis] of real time. Ktor's timeouts and retry delays run outside a test scheduler, and a
 * regression should fail the test, not hang the build.
 */
internal fun capped(millis: Long = 10_000, block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(millis, block) }

internal fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, jsonHeaders)

internal fun MockRequestHandleScope.respondJson(body: ByteReadChannel, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, jsonHeaders)

/**
 * A response body that sends [start], then nothing more. Call it from a handler. MockEngine leaves a body open
 * when its call is cancelled, so a body that stops arriving would hang the test; the curl engine closes it, and so
 * does this one.
 */
internal suspend fun stalledBody(start: String): ByteChannel {
    val body = ByteChannel()
    body.writeStringUtf8(start)
    body.flush()
    // The handler runs in a child of the call's job.
    @OptIn(ExperimentalCoroutinesApi::class)
    currentCoroutineContext()[Job]?.parent?.invokeOnCompletion { cause -> body.cancel(cause) }
    return body
}

/**
 * A TMDB that answers each request by its path from [bodies] (`"/3/movie/603"` to its JSON) and any other with a
 * 404, and records every request in order. Pass [handler] to [mockProvider].
 */
internal class TmdbResponder(private val bodies: Map<String, String> = emptyMap()) {
    // A provider may send requests in parallel (a search's alternative-titles lookups).
    private val lock = Mutex()
    private val recorded = mutableListOf<HttpRequestData>()

    val requests: List<HttpRequestData> get() = recorded.toList()

    val paths: List<String> get() = requests.map { it.url.encodedPath }

    val handler: MockRequestHandler = { request ->
        lock.withLock { recorded += request }
        val body = bodies[request.url.encodedPath]
        if (body != null) respondJson(body) else respondJson(TMDB_NOT_FOUND, HttpStatusCode.NotFound)
    }
}
