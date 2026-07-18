package com.jedon.kellikanvas.ui.tv

import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceFormFactorTest {
    @Test
    fun leanbackFeatureIsDetected() {
        val context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context.packageManager)
            .setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        assertThat(context.isTelevisionUi()).isTrue()
    }

    @Test
    fun nonLeanbackPhoneDefaultsFalse() {
        val context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context.packageManager)
            .setSystemFeature(PackageManager.FEATURE_LEANBACK, false)
        Shadows.shadowOf(context.packageManager)
            .setSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY, false)
        // Default Robolectric config is not television UI mode.
        assertThat(context.isTelevisionUi()).isFalse()
    }
}
