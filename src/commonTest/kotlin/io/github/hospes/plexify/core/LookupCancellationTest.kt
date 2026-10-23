package io.github.hospes.plexify.core

import io.github.hospes.plexify.data.MetadataCache
import io.github.hospes.plexify.data.tmdb.TmdbCredentials
import io.github.hospes.plexify.data.tmdb.TmdbProvider
import io.github.hospes.plexify.data.tmdb.TmdbTimeouts
import io.github.hospes.plexify.domain.model.OperationMode
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A run cancelled while TMDB is looking a file up stops there; the file doesn't count as failed. */
class LookupCancellationTest {

    private val credentials = TmdbCredentials(apiKey = "secret-key", accessToken = null, source = TmdbCredentials.Source.USER)
    private val timeouts = TmdbTimeouts(connectMillis = 5_000, requestMillis = 5_000, retries = 0, optionalRequestMillis = 5_000)

    // The search's awaitAll() would notice the cancellation on its own; the season fetch has nothing else that does.
    @Test
    fun `a run cancelled during a season fetch counts no file as failed`() = runBlocking {
        withTimeout(10_000) {
            val requestStarted = CompletableDeferred<Unit>()
            val provider = TmdbProvider(credentials, timeouts) {
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("search/multi")) {
                        return@MockEngine respond(
                            """{"results": [{"media_type": "tv", "id": 1, "name": "Ghosts", "original_name": "Ghosts", "first_air_date": "2019-04-06"}]}""",
                            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }
                    requestStarted.complete(Unit)
                    awaitCancellation()
                }
            }
            val processor = MediaProcessor(
                metadataService = MetadataService(listOf(provider), NamingStrategy.Jellyfin),
                fileOrganizer = RecordingOrganizer(),
                cache = MetadataCache(),
            )
            val root = Path(SystemTemporaryDirectory, "plexify-cancellation-test-${Random.nextLong().toULong()}")
            val file = Path(root, "Ghosts.S04E01.1080p.WEB.mkv")
            SystemFileSystem.createDirectories(root)
            try {
                SystemFileSystem.sink(file).buffered().close()
                var finished = false
                val run = launch {
                    with(LoggingContext()) { processor.process(file, Path(root, "library"), OperationMode.HARDLINK, isTestMode = true) }
                    finished = true
                }
                requestStarted.await()
                run.cancelAndJoin()

                assertFalse(finished, "the run went on after it was cancelled")
                assertEquals(0, processor.stats.failed)
                assertEquals(0, processor.stats.skipped)
            } finally {
                SystemFileSystem.delete(file, mustExist = false)
                SystemFileSystem.delete(root, mustExist = false)
            }
        }
    }
}
