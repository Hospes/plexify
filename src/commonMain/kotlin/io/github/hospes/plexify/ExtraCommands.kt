package io.github.hospes.plexify

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

// This class used as example of what can do Clikt

object ExtraCommands : CliktCommand(name = "extra") {
    override fun help(context: Context): String = "This is extra command"
    override fun run() {
        echo("Running extra command")
    }
}

object AnotherExtraCommands : CliktCommand(name = "extra2") {
    override fun help(context: Context): String = "This is another extra command (extra2)"
    override fun run() {
        echo("Running another extra command")
    }
}