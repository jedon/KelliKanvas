@file:Suppress("DEPRECATION")

package com.jedon.kellikanvas.dream

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.jedon.kellikanvas.platform.ambient.CapabilityReport
import com.jedon.kellikanvas.platform.ambient.CapabilityStatus

class DreamCapabilityProbe(
    private val context: Context,
) {
    fun probe(service: ComponentName): CapabilityReport {
        val declared =
            try {
                context.packageManager.getServiceInfo(
                    service,
                    0,
                )
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        val settingAvailable =
            context.packageManager.resolveActivity(
                Intent(Settings.ACTION_DREAM_SETTINGS),
                0,
            ) != null
        return CapabilityReport(
            declared = declared,
            settingAvailable = settingAvailable,
            deviceStatus =
            if (declared) {
                CapabilityStatus.CANDIDATE_UNVERIFIED
            } else {
                CapabilityStatus.UNAVAILABLE
            },
        )
    }
}
