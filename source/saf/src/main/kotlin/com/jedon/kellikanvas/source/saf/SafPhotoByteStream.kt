package com.jedon.kellikanvas.source.saf

import com.jedon.kellikanvas.source.PhotoByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import okio.buffer
import okio.source
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class SafPhotoByteStream(
    private val opened: SafOpenDocument,
    contentLength: Long?,
) : PhotoByteStream(contentLength) {
    private val closed = AtomicBoolean()
    private val source =
        try {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(opened.descriptor).source().buffer()
        } catch (failure: Throwable) {
            opened.close()
            throw failure
        }

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed.get()) { "SAF photo stream is closed" }
        coroutineContext.ensureActive()
        return try {
            opened.beforeRead()
            runInterruptible(Dispatchers.IO) {
                source.read(sink, byteCount)
            }.also { read ->
                if (read > 0) opened.onBytesRead(read)
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
            opened.close()
        }
    }

    override fun toString(): String = "SafPhotoByteStream(contentLength=$contentLength, closed=${closed.get()})"
}
