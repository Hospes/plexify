package io.github.hospes.plexify.logging

class LoggingContext(val depth: Int = 0)

private const val INDENT_STRING = "  " // 2 spaces

context(ctx: LoggingContext)
fun log(message: String) {
    val prefix = INDENT_STRING.repeat(ctx.depth)
    println("$prefix$message")
}

context(ctx: LoggingContext)
fun <T> withIndent(block: context(LoggingContext) () -> T): T {
    val newContext = LoggingContext(ctx.depth + 1)
    return block(newContext)
}

context(ctx: LoggingContext)
suspend fun <T> withIndentSuspended(block: suspend context(LoggingContext) () -> T): T {
    val newContext = LoggingContext(ctx.depth + 1)
    return block(newContext)
}
