package com.jedon.kellikanvas.system

import com.jedon.kellikanvas.permission.PermissionRowId
import com.jedon.kellikanvas.permission.PermissionSnapshot
import com.jedon.kellikanvas.permission.PermissionStatus

/** One label + status line on the System screen. */
data class SystemStatusRow(
    val label: String,
    val status: String,
)

fun formatVersionLabel(
    versionName: String,
    versionCode: Long,
): String = "$versionName (build $versionCode)"

fun formatAndroidVersionLabel(
    release: String,
    sdkInt: Int,
): String = "Android $release (SDK $sdkInt)"

fun formFactorLabel(television: Boolean): String = if (television) "Television" else "Phone / tablet"

fun installPermissionStatusLabel(granted: Boolean): String = if (granted) "Granted" else "Not granted"

/** Permission rows plus the SAF folder-grant count, always visible (unlike the startup gate). */
fun permissionStatusRows(
    snapshot: PermissionSnapshot,
    safReadGrantCount: Int,
): List<SystemStatusRow> = snapshot.rows.map { row ->
    SystemStatusRow(
        label = permissionLabel(row.id),
        status = permissionStatusLabel(row.status),
    )
} + SystemStatusRow(
    label = "SAF folder grants",
    status = safGrantCountLabel(safReadGrantCount),
)

fun safGrantCountLabel(count: Int): String = when (count) {
    0 -> "No folders granted"
    1 -> "1 folder granted"
    else -> "$count folders granted"
}

private fun permissionLabel(id: PermissionRowId): String = when (id) {
    PermissionRowId.Internet -> "Internet"
    PermissionRowId.LocalNetwork -> "Local network"
    PermissionRowId.ActivityRecognition -> "Activity recognition"
    PermissionRowId.BodySensors -> "Body sensors"
}

private fun permissionStatusLabel(status: PermissionStatus): String = when (status) {
    PermissionStatus.GrantedAtInstall -> "Granted at install"
    PermissionStatus.Granted -> "Granted"
    PermissionStatus.Denied -> "Not granted"
    PermissionStatus.NotApplicable -> "Not applicable on this Android version"
}
