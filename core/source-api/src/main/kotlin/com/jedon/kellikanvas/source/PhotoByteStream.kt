package com.jedon.kellikanvas.source

import okio.Buffer
import java.io.Closeable

/**
 * A closeable, streaming photo body.
 *
 * Implementations must append at most [byteCount] bytes, return `-1` at end of stream, close
 * underlying resources promptly, and let cancellation exceptions propagate unchanged.
 */
interface PhotoByteStream : Closeable {
    val contentLength: Long?

    suspend fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long
}

fun validateReadByteCount(byteCount: Long): Long {
    require(byteCount > 0) { "Read byte count must be positive" }
    return byteCount
}

fun validateContentLength(contentLength: Long?): Long? {
    require(contentLength == null || contentLength >= 0) {
        "Content length must be nonnegative when known"
    }
    return contentLength
}
