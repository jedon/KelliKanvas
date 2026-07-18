package com.jedon.kellikanvas.feature.slideshow

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.source.PhotoByteStream
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotoBitmapLoaderTest {

    @Before
    fun setUp() {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
    }

    private fun createTestImageBytes(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(format, 100, out)
        return out.toByteArray()
    }

    @Test
    fun `decode tiny valid PNG image without downsampling`() = runTest {
        val bytes = createTestImageBytes(100, 100)
        val stream = FakePhotoByteStream(bytes)

        val decoded = PhotoBitmapLoader.decode(stream, maxEdgePx = 100)

        assertThat(decoded).isNotNull()
        assertThat(decoded.width).isEqualTo(100)
        assertThat(decoded.height).isEqualTo(100)
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun `decode and downsample PNG image to match maxEdgePx`() = runTest {
        // Original: 400x200. maxEdgePx = 100.
        // maxOf(400, 200) = 400.
        // 400 / inSampleSize <= 100 -> inSampleSize = 4.
        // Decoded size should be 100x50.
        val bytes = createTestImageBytes(400, 200)
        val stream = FakePhotoByteStream(bytes)

        val decoded = PhotoBitmapLoader.decode(stream, maxEdgePx = 100)

        assertThat(decoded).isNotNull()
        assertThat(decoded.width).isEqualTo(100)
        assertThat(decoded.height).isEqualTo(50)
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun `sampleSizeFor downsamples 4K frames to display edge`() {
        assertThat(PhotoBitmapLoader.sampleSizeFor(3840, 2160, maxEdgePx = 1920)).isEqualTo(2)
        assertThat(PhotoBitmapLoader.sampleSizeFor(3840, 2160, maxEdgePx = 960)).isEqualTo(4)
        assertThat(PhotoBitmapLoader.sampleSizeFor(800, 600, maxEdgePx = 1920)).isEqualTo(1)
    }

    @Test
    fun `decode closes stream even when decode fails`() = runTest {
        val invalidBytes = byteArrayOf(1, 2, 3, 4, 5)
        val stream = FakePhotoByteStream(invalidBytes)

        var threw = false
        try {
            PhotoBitmapLoader.decode(stream, maxEdgePx = 100)
        } catch (e: IOException) {
            threw = true
        }
        assertThat(threw).isTrue()
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun `decode throws when image exceeds 40 MiB limit`() = runTest {
        val largeStream = object : PhotoByteStream(50 * 1024 * 1024L) {
            var closed = false
            override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                val count = minOf(byteCount, 8192L)
                repeat(count.toInt()) {
                    sink.writeByte(0)
                }
                return count
            }
            override fun close() {
                closed = true
            }
        }

        var threw = false
        try {
            PhotoBitmapLoader.decode(largeStream, maxEdgePx = 100)
        } catch (e: IllegalArgumentException) {
            threw = true
            assertThat(e.message).contains("40 MiB")
        }
        assertThat(threw).isTrue()
        assertThat(largeStream.closed).isTrue()
    }

    @Test
    fun `decode rejects invalid maxEdgePx`() = runTest {
        val bytes = createTestImageBytes(10, 10)
        val stream = FakePhotoByteStream(bytes)

        var threw = false
        try {
            PhotoBitmapLoader.decode(stream, maxEdgePx = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertThat(threw).isTrue()
        assertThat(stream.closed).isTrue()
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
}
