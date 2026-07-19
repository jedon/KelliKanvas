package com.jedon.kellikanvas.permission

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PermissionCoordinatorTest {
    @Test
    fun `internet is always granted at install`() {
        val snapshot = coordinator(sdkInt = LOCAL_NETWORK_RUNTIME_SDK).snapshot()

        assertThat(snapshot.statusOf(PermissionRowId.Internet))
            .isEqualTo(PermissionStatus.GrantedAtInstall)
    }

    @Test
    fun `local network is denied when sdk requires it and permission missing`() {
        val snapshot = coordinator(sdkInt = LOCAL_NETWORK_RUNTIME_SDK).snapshot()

        assertThat(snapshot.statusOf(PermissionRowId.LocalNetwork))
            .isEqualTo(PermissionStatus.Denied)
    }

    @Test
    fun `local network is granted when permission present on requiring sdk`() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(ACCESS_LOCAL_NETWORK)

        val snapshot = coordinator(app, sdkInt = LOCAL_NETWORK_RUNTIME_SDK).snapshot()

        assertThat(snapshot.statusOf(PermissionRowId.LocalNetwork))
            .isEqualTo(PermissionStatus.Granted)
    }

    @Test
    fun `local network is not applicable on older sdk`() {
        val snapshot = coordinator(sdkInt = 35).snapshot()

        assertThat(snapshot.statusOf(PermissionRowId.LocalNetwork))
            .isEqualTo(PermissionStatus.NotApplicable)
    }

    @Test
    fun `activity recognition and body sensors map granted and denied`() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        val snapshot = coordinator(app, sdkInt = LOCAL_NETWORK_RUNTIME_SDK).snapshot()

        assertThat(snapshot.statusOf(PermissionRowId.ActivityRecognition))
            .isEqualTo(PermissionStatus.Granted)
        assertThat(snapshot.statusOf(PermissionRowId.BodySensors))
            .isEqualTo(PermissionStatus.Denied)
    }

    @Test
    fun `shouldShowGate is true only when local network denied`() {
        val app = RuntimeEnvironment.getApplication()
        val denied = coordinator(app, sdkInt = LOCAL_NETWORK_RUNTIME_SDK)

        assertThat(denied.shouldShowGate(denied.snapshot())).isTrue()

        Shadows.shadowOf(app).grantPermissions(ACCESS_LOCAL_NETWORK)
        val granted = coordinator(app, sdkInt = LOCAL_NETWORK_RUNTIME_SDK)
        assertThat(granted.shouldShowGate(granted.snapshot())).isFalse()
    }

    @Test
    fun `shouldShowGate is false when local network granted even if sensors denied`() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(ACCESS_LOCAL_NETWORK)

        val coordinator = coordinator(app, sdkInt = LOCAL_NETWORK_RUNTIME_SDK)
        val snapshot = coordinator.snapshot()

        assertThat(snapshot.statusOf(PermissionRowId.ActivityRecognition))
            .isEqualTo(PermissionStatus.Denied)
        assertThat(snapshot.statusOf(PermissionRowId.BodySensors))
            .isEqualTo(PermissionStatus.Denied)
        assertThat(coordinator.shouldShowGate(snapshot)).isFalse()
    }

    @Test
    fun `shouldShowGate is false when local network not applicable`() {
        val coordinator = coordinator(sdkInt = 35)

        assertThat(coordinator.shouldShowGate(coordinator.snapshot())).isFalse()
    }

    @Test
    fun `appSettingsIntent targets package details settings`() {
        val app = RuntimeEnvironment.getApplication()
        val intent = coordinator(app).appSettingsIntent()

        assertThat(intent.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(intent.data).isEqualTo(Uri.fromParts("package", app.packageName, null))
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `shouldDisplayGate respects sessionSkip when local network denied`() {
        val coordinator = coordinator(sdkInt = LOCAL_NETWORK_RUNTIME_SDK)
        val snapshot = coordinator.snapshot()

        assertThat(coordinator.shouldDisplayGate(sessionSkip = false, snapshot)).isTrue()
        assertThat(coordinator.shouldDisplayGate(sessionSkip = true, snapshot)).isFalse()
    }

    @Test
    fun `runtimePermission maps requestable rows and skips internet`() {
        val requiring = coordinator(sdkInt = LOCAL_NETWORK_RUNTIME_SDK)
        assertThat(requiring.runtimePermission(PermissionRowId.Internet)).isNull()
        assertThat(requiring.runtimePermission(PermissionRowId.LocalNetwork))
            .isEqualTo(ACCESS_LOCAL_NETWORK)
        assertThat(requiring.runtimePermission(PermissionRowId.ActivityRecognition))
            .isEqualTo(Manifest.permission.ACTIVITY_RECOGNITION)
        assertThat(requiring.runtimePermission(PermissionRowId.BodySensors))
            .isEqualTo(Manifest.permission.BODY_SENSORS)

        val older = coordinator(sdkInt = 35)
        assertThat(older.runtimePermission(PermissionRowId.LocalNetwork)).isNull()
    }

    private fun coordinator(
        context: android.content.Context = RuntimeEnvironment.getApplication(),
        sdkInt: Int = 35,
    ): PermissionCoordinator = PermissionCoordinator(context, sdkInt)

    private fun PermissionSnapshot.statusOf(id: PermissionRowId): PermissionStatus = rows.first { it.id == id }.status

    private companion object {
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        const val LOCAL_NETWORK_RUNTIME_SDK = 37
    }
}
