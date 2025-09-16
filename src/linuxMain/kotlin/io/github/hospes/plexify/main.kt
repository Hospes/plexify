package io.github.hospes.plexify

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>): Unit = App
    .subcommands(ExtraCommands, AnotherExtraCommands)
    .main(args)