package com.jedon.kellikanvas.source.smb

import com.jedon.kellikanvas.source.PhotoByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import okio.buffer
import okio.source
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class SmbPhotoByteStream(
    private val input: InputStream,
    contentLength: Long?,
    private val onClose: () -> Unit,
) : PhotoByteStream(contentLength) {
    private val closed = AtomicBoolean()
    private val source = input.source().buffer()

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed.get()) { "SMB photo stream is closed" }
        coroutineContext.ensureActive()
        return try {
            runInterruptible(Dispatchers.IO) {
                source.read(sink, byteCount)
            }
        } catch (failure: CancellationException) {
            close()
            throw failure
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            source.close()
        } finally {
            onClose()
        }
    }

    override fun toString(): String = "SmbPhotoByteStream(contentLength=$contentLength, closed=${closed.get()})"
}
