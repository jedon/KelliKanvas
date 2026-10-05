package com.jedon.kellikanvas.renderer.surface

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.TransitionType
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class PhotoRenderingTest {
    @Test fun crossfadeSlidesPanZoomAndCutNeverExposeABlackLoadingFrame() {
        ActivityScenario.launch(PhotoRenderTestActivity::class.java).use { scenario ->
            for (type in listOf(TransitionType.CROSSFADE, TransitionType.SLIDE_LEFT, TransitionType.SLIDE_RIGHT, TransitionType.PAN_ZOOM, TransitionType.CUT)) {
                scenario.onActivity { it.photo.showFrame(frame(Color.RED)) }
                awaitColor(Color.RED)
                val completed = AtomicBoolean(false)
                scenario.onActivity { it.photo.transitionTo(frame(Color.GREEN), type, 1_200) { completed.set(true) } }
                val deadline = SystemClock.elapsedRealtime() + 10_000
                var mixed = false
                while (!completed.get() && SystemClock.elapsedRealtime() < deadline) {
                    val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                    try {
                        for (column in 1..9) {
                            val color = screenshot.getPixel(screenshot.width * column / 10, screenshot.height / 2)
                            assertThat(Color.red(color) + Color.green(color)).isGreaterThan(180)
                            if (Color.red(color) > 30 && Color.green(color) > 30) mixed = true
                        }
                    } finally {
                        screenshot.recycle()
                    }
                    SystemClock.sleep(40)
                }
                assertThat(completed.get()).isTrue()
                if (type == TransitionType.CROSSFADE || type == TransitionType.PAN_ZOOM) assertThat(mixed).isTrue()
                awaitColor(Color.GREEN)
            }
        }
    }

    @Test fun cancelledTransitionRestoresCurrentPhotoAndNextCutStillWorks() {
        ActivityScenario.launch(PhotoRenderTestActivity::class.java).use { scenario ->
            awaitColor(Color.RED)
            val completed = AtomicBoolean(false)
            scenario.onActivity { it.photo.transitionTo(frame(Color.GREEN), TransitionType.CROSSFADE, 2_000) { completed.set(true) } }
            SystemClock.sleep(300)
            scenario.onActivity { it.photo.cancelTransition() }
            awaitColor(Color.RED)
            assertThat(completed.get()).isFalse()
            scenario.onActivity { it.photo.transitionTo(frame(Color.GREEN), TransitionType.CUT, 0) {} }
            awaitColor(Color.GREEN)
        }
    }

    @Test fun explicitlySelectedFadeThroughBlackRunsWithoutALoadingPause() {
        ActivityScenario.launch(PhotoRenderTestActivity::class.java).use { scenario ->
            awaitColor(Color.RED)
            val completed = AtomicBoolean(false)
            scenario.onActivity { it.photo.transitionTo(frame(Color.GREEN), TransitionType.FADE_THROUGH_BLACK, 2_000) { completed.set(true) } }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var darkPhase = false
            while (!completed.get() && SystemClock.elapsedRealtime() < deadline) {
                val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                val color = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
                darkPhase = darkPhase || Color.red(color) + Color.green(color) < 80
                screenshot.recycle()
                SystemClock.sleep(40)
            }
            assertThat(completed.get()).isTrue()
            assertThat(darkPhase).isTrue()
            awaitColor(Color.GREEN)
        }
    }

    private fun frame(color: Int) = Bitmap.createBitmap(384, 216, Bitmap.Config.RGB_565).apply { eraseColor(color) }

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
