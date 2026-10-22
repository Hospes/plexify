package io.github.hospes.plexify

import io.github.hospes.plexify.core.MediaProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

class ExitStatusTest {

    private fun stats(organized: Int = 0, skipped: Int = 0, failed: Int = 0) = MediaProcessor.Stats().apply {
        this.organized = organized
        this.skipped = skipped
        this.failed = failed
    }

    @Test
    fun `a run where nothing failed exits 0`() {
        assertEquals(EXIT_OK, exitStatus(stats()))
        assertEquals(EXIT_OK, exitStatus(stats(organized = 3)))
    }

    @Test
    fun `skipped files don't fail the run`() {
        assertEquals(EXIT_OK, exitStatus(stats(organized = 1, skipped = 2)))
    }

    @Test
    fun `any failed file fails the run`() {
        assertEquals(EXIT_FAILED, exitStatus(stats(failed = 1)))
        assertEquals(EXIT_FAILED, exitStatus(stats(organized = 5, skipped = 2, failed = 1)))
    }
}
