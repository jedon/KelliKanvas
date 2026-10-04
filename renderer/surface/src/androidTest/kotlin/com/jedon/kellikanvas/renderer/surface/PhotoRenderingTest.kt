package com.jedon.kellikanvas.renderer.surface

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoRenderingTest {
    @Test fun photoPixelsSurviveNavigationAlphaAndFrameReplacement() {
        ActivityScenario.launch(PhotoRenderTestActivity::class.java).use { scenario ->
            awaitColor(Color.RED)
            scenario.onActivity { activity -> activity.photo.alpha = 0f }
            scenario.onActivity { activity -> activity.photo.alpha = 1f }
            awaitColor(Color.RED)
            scenario.onActivity { activity ->
                activity.photo.clearFrame()
                activity.photo.showFrame(Bitmap.createBitmap(384, 216, Bitmap.Config.RGB_565).apply { eraseColor(Color.GREEN) })
            }
            awaitColor(Color.GREEN)
            scenario.recreate()
            awaitColor(Color.RED)
        }
    }
    private fun awaitColor(expected: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var actual = Color.BLACK
        var overlay = Color.BLACK
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            if (screenshot != null) {
                actual = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
                // Android navigation bars can sit below the overlay, so inspect its horizontal band.
                overlay = (80 until screenshot.height).step(10).map { y -> screenshot.getPixel(screenshot.width / 2, y) }.firstOrNull { Color.blue(it) > 200 && Color.red(it) < 30 && Color.green(it) < 30 } ?: Color.BLACK
                screenshot.recycle()
            }
            if (actual == expected && overlay == Color.BLUE) return
            SystemClock.sleep(50)
        }
        assertThat(actual).isEqualTo(expected)
        assertThat(overlay).isEqualTo(Color.BLUE)
    }
}
