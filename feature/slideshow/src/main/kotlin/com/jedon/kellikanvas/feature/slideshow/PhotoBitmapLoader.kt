package com.jedon.kellikanvas.feature.slideshow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jedon.kellikanvas.source.PhotoByteStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import java.io.IOException

object PhotoBitmapLoader {
    /** Compressed payload cap (Frame TV 4K PNGs are typically 8–22 MiB). */
    const val MAX_COMPRESSED_BYTES: Long = 40L * 1024L * 1024L

    suspend fun decode(stream: PhotoByteStream, maxEdgePx: Int): Bitmap =
        withContext(Dispatchers.Default) {
            val bytes =
                stream.use { s ->
                    require(maxEdgePx > 0) { "maxEdgePx must be positive" }
                    val buffer = Buffer()
                    var totalRead = 0L
                    while (true) {
                        val toRead = minOf(8192L, MAX_COMPRESSED_BYTES - totalRead + 1L)
                        val read = s.read(buffer, toRead)
                        if (read == -1L) break
                        totalRead += read
                        if (totalRead > MAX_COMPRESSED_BYTES) {
                            throw IllegalArgumentException(
                                "Photo size exceeds maximum limit of 40 MiB",
                            )
                        }
                    }
                    buffer.readByteArray()
                }

            val bounds =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            val srcWidth = bounds.outWidth
            val srcHeight = bounds.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) {
                throw IOException("Failed to decode image bounds")
            }

            var inSampleSize = sampleSizeFor(srcWidth, srcHeight, maxEdgePx)
            var lastError: Throwable? = null
            // OOM backoff: TV heaps often cannot hold a full 4K ARGB decode even when
            // inSampleSize is set — retry with coarser sampling / cheaper config.
            repeat(6) {
                try {
                    val decoded =
                        decodeSampled(bytes, inSampleSize, preferRgb565 = inSampleSize > 1)
                    if (decoded != null) {
                        return@withContext ensureMaxEdge(decoded, maxEdgePx)
                    }
                    lastError = IOException("Failed to decode bitmap")
                } catch (oom: OutOfMemoryError) {
                    lastError = oom
                }
                inSampleSize *= 2
            }
            throw IOException(
                lastError?.message?.takeIf { it.isNotBlank() }
                    ?: "Out of memory decoding image",
                lastError,
            )
        }

    internal fun sampleSizeFor(srcWidth: Int, srcHeight: Int, maxEdgePx: Int): Int {
        var inSampleSize = 1
        val maxEdge = maxOf(srcWidth, srcHeight)
        while (maxEdge / inSampleSize > maxEdgePx) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun decodeSampled(
        bytes: ByteArray,
        inSampleSize: Int,
        preferRgb565: Boolean,
    ): Bitmap? {
        val options =
            BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize.coerceAtLeast(1)
                inPreferredConfig =
                    if (preferRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun ensureMaxEdge(bitmap: Bitmap, maxEdgePx: Int): Bitmap {
        val maxEdge = maxOf(bitmap.width, bitmap.height)
        if (maxEdge <= maxEdgePx) return bitmap
        val scale = maxEdgePx.toFloat() / maxEdge.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled !== bitmap) {
            bitmap.recycle()
        }
        return scaled
    }
}
