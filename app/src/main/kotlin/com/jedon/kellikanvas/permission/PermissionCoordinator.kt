package com.jedon.kellikanvas.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

class PermissionCoordinator(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    fun snapshot(): PermissionSnapshot =
        PermissionSnapshot(
            rows =
            listOf(
                PermissionRow(PermissionRowId.Internet, PermissionStatus.GrantedAtInstall),
                PermissionRow(PermissionRowId.LocalNetwork, localNetworkStatus()),
                PermissionRow(
                    PermissionRowId.ActivityRecognition,
                    runtimeStatus(Manifest.permission.ACTIVITY_RECOGNITION),
                ),
                PermissionRow(
                    PermissionRowId.BodySensors,
                    runtimeStatus(Manifest.permission.BODY_SENSORS),
                ),
            ),
        )

    fun shouldShowGate(snapshot: PermissionSnapshot = snapshot()): Boolean {
        val localNetwork =
            snapshot.rows.firstOrNull { it.id == PermissionRowId.LocalNetwork }
                ?: return false
        return localNetwork.status == PermissionStatus.Denied
    }

    fun shouldDisplayGate(
        sessionSkip: Boolean,
        snapshot: PermissionSnapshot = snapshot(),
    ): Boolean = shouldShowGate(snapshot) && !sessionSkip

    fun runtimePermission(id: PermissionRowId): String? =
        when (id) {
            PermissionRowId.Internet -> null
            PermissionRowId.LocalNetwork ->
                if (sdkInt < LOCAL_NETWORK_RUNTIME_SDK) {
                    null
                } else {
                    ACCESS_LOCAL_NETWORK
                }
            PermissionRowId.ActivityRecognition -> Manifest.permission.ACTIVITY_RECOGNITION
            PermissionRowId.BodySensors -> Manifest.permission.BODY_SENSORS
        }

    fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun localNetworkStatus(): PermissionStatus {
        if (sdkInt < LOCAL_NETWORK_RUNTIME_SDK) {
            return PermissionStatus.NotApplicable
        }
        return runtimeStatus(ACCESS_LOCAL_NETWORK)
    }

    private fun runtimeStatus(permission: String): PermissionStatus =
        if (
            ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            PermissionStatus.Granted
        } else {
            PermissionStatus.Denied
        }

    private companion object {
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        const val LOCAL_NETWORK_RUNTIME_SDK = 37
    }
}
