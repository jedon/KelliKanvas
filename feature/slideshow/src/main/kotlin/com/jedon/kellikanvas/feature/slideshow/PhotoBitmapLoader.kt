package com.jedon.kellikanvas.feature.slideshow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jedon.kellikanvas.source.PhotoByteStream
import okio.Buffer
import java.io.IOException

object PhotoBitmapLoader {
    suspend fun decode(stream: PhotoByteStream, maxEdgePx: Int): Bitmap {
        val bytes = stream.use { s ->
            require(maxEdgePx > 0) { "maxEdgePx must be positive" }

            val buffer = Buffer()
            val maxBytes = 40 * 1024 * 1024L // 40 MiB
            var totalRead = 0L
            while (true) {
                val toRead = minOf(8192L, maxBytes - totalRead + 1L)
                val read = s.read(buffer, toRead)
                if (read == -1L) break
                totalRead += read
                if (totalRead > maxBytes) {
                    throw IllegalArgumentException("Photo size exceeds maximum limit of 40 MiB")
                }
            }
            buffer.readByteArray()
        }

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

        val srcWidth = options.outWidth
        val srcHeight = options.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) {
            throw IOException("Failed to decode image bounds")
        }

        var inSampleSize = 1
        val maxEdge = maxOf(srcWidth, srcHeight)
        while (maxEdge / inSampleSize > maxEdgePx) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            ?: throw IOException("Failed to decode bitmap")
    }
}
