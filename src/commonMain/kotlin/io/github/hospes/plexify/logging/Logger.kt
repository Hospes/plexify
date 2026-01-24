package io.github.hospes.plexify.logging

class LoggingContext(val depth: Int = 0) {
    operator fun plus(increment: Int): LoggingContext = LoggingContext(depth + increment)
}

private const val INDENT_STRING = "  " // 2 spaces

context(ctx: LoggingContext)
fun log(message: String) {
    val prefix = INDENT_STRING.repeat(ctx.depth)
    val arrow = if (ctx.depth > 0) "-> " else ""
    println("$prefix$arrow$message")
}

context(ctx: LoggingContext)
inline fun <R> indent(block: context(LoggingContext) () -> R): R {
    return block(ctx + 1)
}