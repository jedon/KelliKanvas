package com.jedon.kellikanvas.logging

/** One bootstrap step outcome, e.g. `SMB connect 192.168.68.90/Photos`. */
data class BootstrapTraceStep(
    val name: String,
    val ok: Boolean,
    val detail: String? = null,
)

/** The full trace of one bootstrap run plus its final result summary. */
data class BootstrapTraceRecord(
    val startedAtMillis: Long,
    val steps: List<BootstrapTraceStep>,
    val result: String,
)

/**
 * Holds the most recent household-bootstrap trace so the Diagnostics screen can
 * show "last bootstrap trace and result". Follows the [DiagLog] singleton pattern.
 */
object BootstrapTrace {
    @Volatile
    private var lastRecord: BootstrapTraceRecord? = null

    fun record(record: BootstrapTraceRecord) {
        lastRecord = record
    }

    fun last(): BootstrapTraceRecord? = lastRecord

    fun clear() {
        lastRecord = null
    }
}
