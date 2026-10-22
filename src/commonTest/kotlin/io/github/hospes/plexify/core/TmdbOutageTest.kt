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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** A TMDB that stops answering mid-run, through the real provider: the run must not wait it out for every file. */
class TmdbOutageTest {

    private val credentials = TmdbCredentials(apiKey = "secret-key", accessToken = null, source = TmdbCredentials.Source.USER)

    @Test
    fun `when tmdb stops answering only the first files wait and every file counts as failed`() = runBlocking {
        var requests = 0
        val provider = TmdbProvider(credentials, TmdbTimeouts(connectMillis = 200, requestMillis = 200, retries = 0, unreachableAfter = 3)) {
            MockEngine {
                requests++
                awaitCancellation()
            }
        }
        val processor = MediaProcessor(MetadataService(listOf(provider), NamingStrategy.Jellyfin), RecordingOrganizer(), MetadataCache())
        val fileNames = (1..10).map { "Ghosts.S04E${it.toString().padStart(2, '0')}.1080p.WEB.mkv" } + "Dune.Part.Two.2024.1080p.WEB-DL.mkv"

        val dir = Path(SystemTemporaryDirectory, "plexify-outage-test-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(dir)
        val files = fileNames.map { Path(dir, it) }
        try {
            files.forEach { SystemFileSystem.sink(it).buffered().close() }
            // Before the breaker, this run waited out a timeout for each of the 11 files.
            withTimeout(5_000) {
                with(LoggingContext()) {
                    processor.process(dir, Path(dir, "library"), OperationMode.HARDLINK, isTestMode = true)
                }
            }
        } finally {
            files.forEach { SystemFileSystem.delete(it, mustExist = false) }
            SystemFileSystem.delete(dir, mustExist = false)
        }

        assertEquals(3, requests)
        assertEquals(11, processor.stats.failed)
        assertEquals(0, processor.stats.skipped)
        assertEquals(0, processor.stats.organized)
    }
}
