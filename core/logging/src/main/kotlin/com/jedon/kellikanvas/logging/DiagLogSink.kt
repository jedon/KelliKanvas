package com.jedon.kellikanvas.logging

/**
 * Receives every entry appended to [DiagLog], e.g. to mirror into logcat.
 *
 * [throwable] is the original throwable from the log call (null when none was
 * given). It is passed transiently so sinks can emit full stack traces; it is
 * never retained in the ring buffer, which only keeps [DiagLogEntry.throwableSummary].
 */
fun interface DiagLogSink {
    fun onLog(
        entry: DiagLogEntry,
        throwable: Throwable?,
    )
}
