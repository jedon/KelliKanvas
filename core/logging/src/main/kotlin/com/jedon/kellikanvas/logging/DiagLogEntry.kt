package com.jedon.kellikanvas.logging

/**
 * One diagnostic log record.
 *
 * [throwableSummary] is `"<ExceptionSimpleName>: <message>"` when the log call
 * included a throwable, null otherwise.
 */
data class DiagLogEntry(
    val timestampMillis: Long,
    val level: DiagLogLevel,
    val tag: String,
    val message: String,
    val throwableSummary: String? = null,
)
