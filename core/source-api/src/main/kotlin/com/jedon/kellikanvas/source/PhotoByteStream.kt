package com.jedon.kellikanvas.source

import okio.Buffer
import java.io.Closeable

/**
 * A closeable, streaming photo body.
 *
 * Implementations append bytes only through [readAtMostTo], close underlying resources promptly,
 * and let cancellation exceptions propagate unchanged.
 */
abstract class PhotoByteStream(
    contentLength: Long?,
) : Closeable {
    val contentLength: Long? = validateContentLength(contentLength)

    final suspend fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        require(byteCount > 0) { "Read byte count must be positive" }
        val initialSinkSize = sink.size
        val result = readAtMostTo(sink, byteCount)
        val sinkGrowth = sink.size - initialSinkSize

        if (result == EOF) {
            check(sinkGrowth == 0L) { "EOF must not append bytes" }
        } else {
            check(result > 0) { "Read result must be positive or EOF" }
            check(result <= byteCount) { "Read result exceeds requested byte count" }
            check(sinkGrowth == result) { "Read result must equal sink growth" }
        }
        return result
    }

    protected abstract suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long

    private companion object {
        const val EOF: Long = -1

        fun validateContentLength(contentLength: Long?): Long? {
            require(contentLength == null || contentLength >= 0) {
                "Content length must be nonnegative when known"
            }
            return contentLength
        }
    }
}
