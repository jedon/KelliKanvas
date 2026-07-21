package com.jedon.kellikanvas.system

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.permission.PermissionRow
import com.jedon.kellikanvas.permission.PermissionRowId
import com.jedon.kellikanvas.permission.PermissionSnapshot
import com.jedon.kellikanvas.permission.PermissionStatus
import org.junit.Test

class SystemInfoTest {
    @Test
    fun `formatVersionLabel combines name and code`() {
        assertThat(formatVersionLabel("1.0.14", 15)).isEqualTo("1.0.14 (build 15)")
    }

    @Test
    fun `formatAndroidVersionLabel combines release and sdk`() {
        assertThat(formatAndroidVersionLabel("16", 37)).isEqualTo("Android 16 (SDK 37)")
    }

    @Test
    fun `formFactorLabel distinguishes tv from phone`() {
        assertThat(formFactorLabel(television = true)).isEqualTo("Television")
        assertThat(formFactorLabel(television = false)).isEqualTo("Phone / tablet")
    }

    @Test
    fun `installPermissionStatusLabel maps grant state`() {
        assertThat(installPermissionStatusLabel(granted = true)).isEqualTo("Granted")
        assertThat(installPermissionStatusLabel(granted = false)).isEqualTo("Not granted")
    }

    @Test
    fun `safGrantCountLabel pluralizes folder counts`() {
        assertThat(safGrantCountLabel(0)).isEqualTo("No folders granted")
        assertThat(safGrantCountLabel(1)).isEqualTo("1 folder granted")
        assertThat(safGrantCountLabel(3)).isEqualTo("3 folders granted")
    }

    @Test
    fun `permissionStatusRows maps every snapshot row and appends saf grants`() {
        val snapshot = PermissionSnapshot(
            rows = listOf(
                PermissionRow(PermissionRowId.Internet, PermissionStatus.GrantedAtInstall),
                PermissionRow(PermissionRowId.LocalNetwork, PermissionStatus.NotApplicable),
                PermissionRow(PermissionRowId.ActivityRecognition, PermissionStatus.Granted),
                PermissionRow(PermissionRowId.BodySensors, PermissionStatus.Denied),
            ),
        )

        val rows = permissionStatusRows(snapshot, safReadGrantCount = 2)

        assertThat(rows).containsExactly(
            SystemStatusRow("Internet", "Granted at install"),
            SystemStatusRow("Local network", "Not applicable on this Android version"),
            SystemStatusRow("Activity recognition", "Granted"),
            SystemStatusRow("Body sensors", "Not granted"),
            SystemStatusRow("SAF folder grants", "2 folders granted"),
        ).inOrder()
    }

    @Test
    fun `permissionStatusRows shows denied local network on requiring sdk`() {
        val snapshot = PermissionSnapshot(
            rows = listOf(
                PermissionRow(PermissionRowId.LocalNetwork, PermissionStatus.Denied),
            ),
        )

        val rows = permissionStatusRows(snapshot, safReadGrantCount = 0)

        assertThat(rows).containsExactly(
            SystemStatusRow("Local network", "Not granted"),
            SystemStatusRow("SAF folder grants", "No folders granted"),
        ).inOrder()
    }
}
