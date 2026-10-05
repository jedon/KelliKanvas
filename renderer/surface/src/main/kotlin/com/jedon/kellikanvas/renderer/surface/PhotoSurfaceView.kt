package com.jedon.kellikanvas.renderer.surface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import com.jedon.kellikanvas.logging.DiagLog

private const val TAG = "PhotoSurfaceView"

/**
 * Still-photo texture composed in the same window as navigation and status overlays.
 *
 * Hardware video players decode into a Surface without a Java-heap ARGB frame.
 * A separate SurfaceView can remain behind Compose's opaque navigation layer after
 * its transition. TextureView participates in that layer's alpha and transforms.
 * Keep one bounded RGB_565 bitmap and a panel-sized texture buffer.
 */
class PhotoSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs),
    TextureView.SurfaceTextureListener {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val drawMatrix = Matrix()
    private var frame: Bitmap? = null
    private var onPresented: (() -> Unit)? = null
    private var panelWidth = 0
    private var panelHeight = 0

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    fun setFixedPanelSize(widthPx: Int, heightPx: Int) {
        if (widthPx > 0 && heightPx > 0) {
            panelWidth = widthPx
            panelHeight = heightPx
            surfaceTexture?.setDefaultBufferSize(widthPx, heightPx)
        }
    }

    fun showFrame(bitmap: Bitmap?, onPresented: (() -> Unit)? = null) {
        frame = bitmap
        this.onPresented = onPresented
        redraw()
    }

    fun clearFrame() {
        onPresented = null
        frame = null
        redraw()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        configureBuffer(surface, width, height)
        redraw()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        configureBuffer(surface, width, height)
        redraw()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        val callback = onPresented
        onPresented = null
        // Texture updates arrive inside drawing. Reordering siblings there can skip the
        // outgoing view for one traversal; commit transitions after that draw has finished.
        if (callback != null) post { callback() }
    }

    private fun configureBuffer(surface: SurfaceTexture, width: Int, height: Int) {
        surface.setDefaultBufferSize(panelWidth.takeIf { it > 0 } ?: width, panelHeight.takeIf { it > 0 } ?: height)
    }

    private fun redraw() {
        if (!isAvailable) return
        val canvas: Canvas? =
            try {
                lockCanvas()
            } catch (failure: Exception) {
                DiagLog.w(TAG, "Failed to lock canvas; skipping frame", failure)
                return
            }
        if (canvas == null) {
            DiagLog.w(TAG, "Canvas lock returned null; skipping frame")
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
                unlockCanvasAndPost(canvas)
            } catch (failure: Exception) {
                // Surface gone during unlock — nothing to recover.
                DiagLog.w(TAG, "Failed to unlock canvas after draw", failure)
            }
        }
    }
}
