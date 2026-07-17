package com.jedon.kellikanvas.source

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertThrows
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
    fun `photo stream rejects nonpositive read bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateReadByteCount(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateReadByteCount(-1)
        }
    }

    @Test
    fun `photo stream rejects negative content length`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateContentLength(-1)
        }
        assertThat(validateContentLength(null)).isNull()
    }

    private class FakePhotoByteStream(
        private val bytes: ByteArray,
    ) : PhotoByteStream {
        override val contentLength = validateContentLength(bytes.size.toLong())
        var closed = false
        private var offset = 0

        override suspend fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            validateReadByteCount(byteCount)
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
}
