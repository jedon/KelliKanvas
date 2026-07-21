package com.jedon.kellikanvas.logging

/**
 * In-app diagnostic logger backed by a fixed-capacity ring buffer.
 *
 * Thread-safe: appends, [snapshot], and [clear] synchronize on an internal lock.
 * Once [CAPACITY] entries are buffered, each append evicts the oldest entry.
 */
object DiagLog {
    const val CAPACITY: Int = 500

    private val lock = Any()
    private val entries = ArrayDeque<DiagLogEntry>(CAPACITY)

    @Volatile
    private var sink: DiagLogSink? = null

    fun d(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        append(DiagLogLevel.DEBUG, tag, message, throwable)
    }

    fun i(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        append(DiagLogLevel.INFO, tag, message, throwable)
    }

    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        append(DiagLogLevel.WARN, tag, message, throwable)
    }

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        append(DiagLogLevel.ERROR, tag, message, throwable)
    }

    /** Returns the buffered entries ordered oldest first. */
    fun snapshot(): List<DiagLogEntry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /**
     * Installs [sink] so every appended entry is mirrored to it; pass null to remove.
     * Exceptions thrown by the sink are swallowed.
     *
     * The sink is invoked after the entry has been appended to the buffer, outside the
     * buffer lock, so under concurrent appends the sink call order may differ from the
     * buffer order.
     */
    fun installSink(sink: DiagLogSink?) {
        this.sink = sink
    }

    private fun append(
        level: DiagLogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        val entry =
            DiagLogEntry(
                timestampMillis = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message,
                throwableSummary = throwable?.diagnosticSummary(),
            )
        synchronized(lock) {
            if (entries.size >= CAPACITY) {
                entries.removeFirst()
            }
            entries.addLast(entry)
        }
        try {
            sink?.onLog(entry, throwable)
        } catch (_: Exception) {
            // A misbehaving sink must never break the logging call site.
        }
    }
}
