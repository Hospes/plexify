package io.github.hospes.plexify.logging

class LoggingContext(val depth: Int = 0, val verbose: Boolean = false) {
    operator fun plus(increment: Int): LoggingContext = LoggingContext(depth + increment, verbose)
}

private const val INDENT_STRING = "  " // 2 spaces

/** Always printed, indented by nesting depth. Use for structural messages, warnings and errors. */
context(ctx: LoggingContext)
fun log(message: String) {
    val prefix = INDENT_STRING.repeat(ctx.depth)
    val arrow = if (ctx.depth > 0) "-> " else ""
    println("$prefix$arrow$message")
}

/** Pipeline internals (parsing, cache, providers, scoring). Printed only in verbose mode. */
context(ctx: LoggingContext)
fun debug(message: String) {
    if (ctx.verbose) log(message)
}

/** Per-file outcome lines. Flat single indent in concise mode, depth-indented in verbose mode. */
context(ctx: LoggingContext)
fun status(message: String) {
    if (ctx.verbose) log(message) else println("$INDENT_STRING$message")
}

context(ctx: LoggingContext)
inline fun <R> indent(block: context(LoggingContext) () -> R): R {
    return block(ctx + 1)
}
