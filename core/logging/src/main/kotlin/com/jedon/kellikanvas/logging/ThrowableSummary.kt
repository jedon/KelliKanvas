package com.jedon.kellikanvas.logging

/**
 * Compact `"<ExceptionSimpleName>: <message>"` summary for user-visible diagnostics.
 * Anonymous classes have an empty simpleName; fall back so the summary never
 * renders as ": message".
 */
fun Throwable.diagnosticSummary(): String {
    val name =
        javaClass.simpleName.ifBlank {
            javaClass.name.substringAfterLast('.').ifBlank { "Exception" }
        }
    return "$name: $message"
}
