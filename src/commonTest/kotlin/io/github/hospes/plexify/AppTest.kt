package io.github.hospes.plexify

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.parse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class AppTest {

    // "-tc" must not be read as "-t c": that would title every file "c" and take the template for a source path.
    @Test
    fun `the removed custom template option fails with a reason`() {
        val template = "{CleanTitle}/{CleanTitle}.{ext}"
        val commands = listOf(
            listOf("-tc", template),
            listOf("--template-custom", template),
            listOf("--template-custom=$template"),
            listOf("-tp", "-tc", template),
        )
        for (command in commands) {
            val error = assertFailsWith<BadParameterValue>(command.toString()) {
                App.parse(command + listOf("source", "destination"))
            }
            assertContains(error.message.orEmpty(), "custom templates were removed")
        }
    }
}
