package com.jedon.kellikanvas.renderer.surface

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/** Debug-only real compositor fixture; never included in the distributed APK. */
class PhotoRenderTestActivity : Activity() {
    lateinit var photo: PhotoSurfaceView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parent = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        photo = PhotoSurfaceView(this).apply { setFixedPanelSize(3840, 2160) }
        parent.addView(photo, FrameLayout.LayoutParams(-1, -1))
        parent.addView(
            TextView(this).apply {
                setText(R.string.render_test_overlay)
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLUE)
            },
            FrameLayout.LayoutParams(-1, 80, Gravity.BOTTOM),
        )
        setContentView(parent)
        photo.showFrame(Bitmap.createBitmap(384, 216, Bitmap.Config.RGB_565).apply { eraseColor(Color.RED) })
    }
}
