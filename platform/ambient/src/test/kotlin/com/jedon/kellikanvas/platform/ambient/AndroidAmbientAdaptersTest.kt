package com.jedon.kellikanvas.platform.ambient

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidAmbientAdaptersTest {
    @Test
    fun `sensor inventory works without physical sensors`() {
        val inventory = AndroidSensorInventory(RuntimeEnvironment.getApplication()).inventory()

        assertThat(inventory.sensors).isEmpty()
        assertThat(inventory.capabilities).isEqualTo(AmbientCapabilities.unavailable())
    }

    @Test
    fun `brightness sink changes only supplied window`() {
        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        val sink = WindowBrightnessSink(first.window)

        sink.apply(BrightnessDecision.Sensor(0.42f))

        assertThat(first.window.attributes.screenBrightness).isEqualTo(0.42f)
        assertThat(second.window.attributes.screenBrightness)
            .isEqualTo(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    @Test
    fun `follow TV clears per-window override`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val sink = WindowBrightnessSink(activity.window)
        sink.apply(BrightnessDecision.Schedule(0.7f))

        sink.apply(BrightnessDecision.FollowTv)

        assertThat(activity.window.attributes.screenBrightness)
            .isEqualTo(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    @Test
    fun `diagnostics separates declaration setting and device verification`() {
        val diagnostics =
            AmbientDiagnostics(
                light =
                CapabilityReport(
                    declared = false,
                    settingAvailable = true,
                    deviceStatus = CapabilityStatus.CANDIDATE_UNVERIFIED,
                ),
                presence =
                CapabilityReport(
                    declared = false,
                    settingAvailable = false,
                    deviceStatus = CapabilityStatus.UNAVAILABLE,
                ),
                dream =
                CapabilityReport(
                    declared = true,
                    settingAvailable = true,
                    deviceStatus = CapabilityStatus.CANDIDATE_UNVERIFIED,
                ),
            )

        assertThat(diagnostics.dream.declared).isTrue()
        assertThat(diagnostics.dream.settingAvailable).isTrue()
        assertThat(diagnostics.dream.deviceStatus).isEqualTo(CapabilityStatus.CANDIDATE_UNVERIFIED)
    }

    @Test
    fun `Android permission checker uses public runtime permission state`() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(Manifest.permission.INTERNET)
        val checker = AndroidPermissionChecker(application)

        assertThat(checker.hasPermission(Manifest.permission.INTERNET)).isTrue()
        assertThat(checker.hasPermission("com.jedon.kellikanvas.permission.MISSING")).isFalse()
    }

    @Test
    fun `timezone observer unregisters cleanly`() {
        val application = RuntimeEnvironment.getApplication()
        val source = AndroidTimezoneChangeSource(application)
        var changes = 0
        val registration = source.register { changes++ }

        application.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(changes).isEqualTo(1)

        registration.unregister()
        application.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(changes).isEqualTo(1)
    }

    @Test
    fun `Android elapsed time source is monotonic`() {
        val first = AndroidElapsedTimeSource.nowNanos()
        val second = AndroidElapsedTimeSource.nowNanos()

        assertThat(second).isAtLeast(first)
    }
}
