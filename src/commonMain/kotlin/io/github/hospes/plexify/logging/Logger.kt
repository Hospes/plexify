package io.github.hospes.plexify.logging

import kotlin.native.concurrent.ThreadLocal

// Use a ThreadLocal object to safely store the indentation level.
// This ensures that even in a multi-threaded app, each thread has its own indent level.
@ThreadLocal
private object Indentation {
    var level: Int = 0
}

// Define the string used for each level of indentation.
private const val INDENT_STRING = "  " // 2 spaces

/**
 * A custom logging function that automatically adds the correct indentation.
 */
fun log(message: String) {
    val prefix = INDENT_STRING.repeat(Indentation.level)
    println("$prefix$message")
}

/**
 * A higher-order function to execute a block of code with an increased indent level.
 * It safely resets the indent level afterwards, even if an error occurs.
 */
fun <T> withIndent(block: () -> T): T {
    Indentation.level++
    try {
        return block()
    } finally {
        Indentation.level-- // This will always be called, even on exceptions!
    }
}

suspend fun <T> withIndentSuspended(block: suspend () -> T): T {
    Indentation.level++
    try {
        return block()
    } finally {
        Indentation.level-- // This will always be called, even on exceptions!
    }
}