package com.jedon.kellikanvas.source

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable

class PhotoByteStreamTest {
    @Test
    fun `photo stream is closeable and reads no more than requested`() = runTest {
        val stream = FakePhotoByteStream("photo-data".encodeToByteArray())
        val sink = Buffer()

        assertThat(stream).isInstanceOf(Closeable::class.java)
        assertThat(stream.contentLength).isEqualTo(10)
        assertThat(stream.read(sink, 5)).isEqualTo(5)
        assertThat(sink.readUtf8()).isEqualTo("photo")

        stream.close()
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun `photo stream rejects nonpositive read bounds before delegation`() = runTest {
        val stream = ControlledPhotoByteStream(contentLength = null, result = 1, bytesToWrite = 1)

        assertInvalidReadBound(stream, 0)
        assertInvalidReadBound(stream, -1)

        assertThat(stream.readCalls).isEqualTo(0)
    }

    @Test
    fun `photo stream validates content length at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControlledPhotoByteStream(contentLength = -1, result = -1, bytesToWrite = 0)
        }
        assertThat(
            ControlledPhotoByteStream(contentLength = null, result = -1, bytesToWrite = 0)
                .contentLength,
        ).isNull()
    }

    @Test
    fun `photo stream accepts EOF only without sink growth`() = runTest {
        val stream = ControlledPhotoByteStream(contentLength = 0, result = -1, bytesToWrite = 0)
        val sink = Buffer()

        assertThat(stream.read(sink, 5)).isEqualTo(-1)
        assertThat(sink.size).isEqualTo(0)
    }

    @Test
    fun `photo stream rejects invalid delegate results`() = runTest {
        val invalidResults =
            listOf(
                InvalidRead(result = -2, bytesToWrite = 0, requested = 5),
                InvalidRead(result = 0, bytesToWrite = 0, requested = 5),
                InvalidRead(result = 6, bytesToWrite = 6, requested = 5),
                InvalidRead(result = 3, bytesToWrite = 2, requested = 5),
                InvalidRead(result = -1, bytesToWrite = 1, requested = 5),
            )

        invalidResults.forEach { invalid ->
            val stream =
                ControlledPhotoByteStream(
                    contentLength = null,
                    result = invalid.result,
                    bytesToWrite = invalid.bytesToWrite,
                )

            assertInvalidDelegateResult(stream, invalid.requested)
        }
    }

    @Test
    fun `photo stream propagates cancellation unchanged`() = runTest {
        val cancellation = CancellationException("cancelled")
        val stream =
            ControlledPhotoByteStream(
                contentLength = null,
                result = -1,
                bytesToWrite = 0,
                failure = cancellation,
            )

        try {
            stream.read(Buffer(), 5)
            fail("Expected cancellation")
        } catch (caught: CancellationException) {
            assertThat(caught).isSameInstanceAs(cancellation)
        }
    }

    private suspend fun assertInvalidReadBound(
        stream: ControlledPhotoByteStream,
        byteCount: Long,
    ) {
        try {
            stream.read(Buffer(), byteCount)
            fail("Expected byte count $byteCount to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected caller contract violation.
        }
    }

    private suspend fun assertInvalidDelegateResult(
        stream: ControlledPhotoByteStream,
        byteCount: Long,
    ) {
        try {
            stream.read(Buffer(), byteCount)
            fail("Expected invalid delegate result to be rejected")
        } catch (_: IllegalStateException) {
            // Expected implementation contract violation.
        }
    }

    private class FakePhotoByteStream(
        private val bytes: ByteArray,
    ) : PhotoByteStream(bytes.size.toLong()) {
        var closed = false
        private var offset = 0

        override suspend fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            if (offset == bytes.size) return -1
            val count = minOf(byteCount.toInt(), bytes.size - offset)
            sink.write(bytes, offset, count)
            offset += count
            return count.toLong()
        }

        override fun close() {
            closed = true
        }
    }

    private class ControlledPhotoByteStream(
        contentLength: Long?,
        private val result: Long,
        private val bytesToWrite: Int,
        private val failure: Throwable? = null,
    ) : PhotoByteStream(contentLength) {
        var readCalls = 0

        override suspend fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            readCalls += 1
            failure?.let { throw it }
            repeat(bytesToWrite) {
                sink.writeByte('x'.code)
            }
            return result
        }

        override fun close() = Unit
    }

    private data class InvalidRead(
        val result: Long,
        val bytesToWrite: Int,
        val requested: Long,
    )
}
