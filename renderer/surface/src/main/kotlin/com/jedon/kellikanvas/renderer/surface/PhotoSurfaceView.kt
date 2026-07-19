package com.jedon.kellikanvas.renderer.surface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Full-screen still-photo surface.
 *
 * Hardware video players decode into a Surface without a Java-heap ARGB frame.
 * This view is the still-photo counterpart: draw one RGB_565 (or cheaper) bitmap
 * into the surface buffer sized to the panel, and never keep a second full frame.
 */
class PhotoSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs),
    SurfaceHolder.Callback {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val drawMatrix = Matrix()
    private var frame: Bitmap? = null
    private var surfaceReady = false

    init {
        holder.addCallback(this)
        setZOrderOnTop(false)
    }

    fun setFixedPanelSize(widthPx: Int, heightPx: Int) {
        if (widthPx > 0 && heightPx > 0) {
            holder.setFixedSize(widthPx, heightPx)
        }
    }

    fun showFrame(bitmap: Bitmap?) {
        frame = bitmap
        redraw()
    }

    fun clearFrame() {
        frame = null
        redraw()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        redraw()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        surfaceReady = true
        redraw()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    private fun redraw() {
        if (!surfaceReady) return
        val canvas: Canvas =
            try {
                holder.lockHardwareCanvas() ?: holder.lockCanvas() ?: return
            } catch (_: Exception) {
                return
            }
        try {
            canvas.drawColor(Color.BLACK)
            val bitmap = frame ?: return
            if (bitmap.isRecycled) return
            val viewW = canvas.width.toFloat().coerceAtLeast(1f)
            val viewH = canvas.height.toFloat().coerceAtLeast(1f)
            val bmpW = bitmap.width.toFloat().coerceAtLeast(1f)
            val bmpH = bitmap.height.toFloat().coerceAtLeast(1f)
            val scale =
                if (bmpH > bmpW) {
                    // Portrait: fit
                    minOf(viewW / bmpW, viewH / bmpH)
                } else {
                    // Landscape: crop to fill
                    maxOf(viewW / bmpW, viewH / bmpH)
                }
            val dx = (viewW - bmpW * scale) * 0.5f
            val dy = (viewH - bmpH * scale) * 0.5f
            drawMatrix.reset()
            drawMatrix.setScale(scale, scale)
            drawMatrix.postTranslate(dx, dy)
            canvas.drawBitmap(bitmap, drawMatrix, paint)
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
                // Surface gone during unlock — ignore.
            }
        }
    }

}
