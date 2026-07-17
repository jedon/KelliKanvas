package com.jedon.kellikanvas.source.testing

import com.jedon.kellikanvas.source.PhotoByteStream
import okio.Buffer

/** A bounded, observable stream for adapter contract fakes. */
class FakePhotoByteStream(
    bytes: ByteArray,
    private val maxChunkSize: Int = Int.MAX_VALUE,
    private val beforeRead: suspend (requestedByteCount: Long) -> Unit = {},
    private val onClose: () -> Unit = {},
) : PhotoByteStream(bytes.size.toLong()) {
    private val payload = bytes.copyOf()
    private var offset = 0

    var readCalls: Int = 0
        private set
    var largestReadRequest: Long = 0
        private set
    var bytesRead: Long = 0
        private set
    var closed: Boolean = false
        private set

    init {
        require(maxChunkSize > 0) { "Fake stream chunk size must be positive" }
    }

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed) { "Fake stream is closed" }
        readCalls += 1
        largestReadRequest = maxOf(largestReadRequest, byteCount)
        beforeRead(byteCount)
        if (offset == payload.size) return -1

        val count = minOf(byteCount, maxChunkSize.toLong(), (payload.size - offset).toLong()).toInt()
        sink.write(payload, offset, count)
        offset += count
        bytesRead += count
        return count.toLong()
    }

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }

    override fun toString(): String = "FakePhotoByteStream(contentLength=$contentLength, bytesRead=$bytesRead, closed=$closed)"
}
