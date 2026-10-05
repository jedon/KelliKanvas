package com.jedon.kellikanvas.slideshow

import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SlideshowPlaybackTest {
    @Test fun autoplayWaitsForSlowPrefetchWithoutReplacingTheVisiblePhotoWithLoading() {
        ActivityScenario.launch(SlideshowPlaybackTestActivity::class.java).use { scenario ->
            awaitColor(Color.RED)
            await {
                var opened = false
                scenario.onActivity { opened = it.reads["second"]?.get() == 1 }
                opened
            }
            val deadline = SystemClock.elapsedRealtime() + 3_500
            while (SystemClock.elapsedRealtime() < deadline) {
                assertPhoto(Color.RED)
                assertNoLoading()
                SystemClock.sleep(100)
            }
            scenario.onActivity { it.releaseSecond.complete(Unit) }
            awaitColor(Color.GREEN)
            assertNoLoading()
        }
    }

    @Test fun slowAndUnreadableNasPhotosKeepCurrentPixelsAndNeverShowLoadingAgain() {
        ActivityScenario.launch(SlideshowPlaybackTestActivity::class.java).use { scenario ->
            awaitColor(Color.RED)
            key(scenario, KeyEvent.KEYCODE_DPAD_CENTER) // Pause autoplay; prefetch continues.
            await {
                var opened = false
                scenario.onActivity { opened = it.reads["second"]?.get() == 1 }
                opened
            }
            key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
            repeat(5) {
                assertPhoto(Color.RED)
                assertNoLoading()
                SystemClock.sleep(100)
            }
            scenario.onActivity { it.releaseSecond.complete(Unit) }
            awaitColor(Color.GREEN)
            scenario.onActivity { assertThat(it.reads["second"]?.get()).isEqualTo(1) }
            key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT) // Broken photo is skipped over the current green frame.
            repeat(5) {
                assertPhoto(Color.GREEN)
                assertNoLoading()
                SystemClock.sleep(100)
            }
            awaitColor(Color.YELLOW)
            assertNoLoading()
        }
    }
    private fun key(scenario: ActivityScenario<SlideshowPlaybackTestActivity>, code: Int) {
        scenario.onActivity { activity ->
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }
    private fun assertNoLoading() {
        fun text(node: AccessibilityNodeInfo): String = buildString {
            append(node.text)
            append(node.contentDescription)
            for (index in 0 until node.childCount) node.getChild(index)?.let { append(text(it)) }
        }
        val root = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        assertThat(text(root)).doesNotContain("Loading")
    }
    private fun pixel(): Int {
        val shot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        return try {
            shot.getPixel(shot.width / 2, shot.height / 2)
        } finally {
            shot.recycle()
        }
    }
    private fun assertPhoto(color: Int) = assertThat(pixel()).isEqualTo(color)
    private fun awaitColor(color: Int) = await { pixel() == color }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertThat(condition()).isTrue()
    }
}
