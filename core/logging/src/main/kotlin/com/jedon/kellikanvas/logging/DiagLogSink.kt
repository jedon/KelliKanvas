package com.jedon.kellikanvas.logging

/** Receives every entry appended to [DiagLog], e.g. to mirror into logcat. */
fun interface DiagLogSink {
    fun onLog(entry: DiagLogEntry)
}
