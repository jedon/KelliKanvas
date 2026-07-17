package com.jedon.kellikanvas.platform.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

enum class InstallResult {
    PERMISSION_REQUIRED,
    CONFIRMATION_LAUNCHED,
}

interface InstallPlatform {
    fun canRequestPackageInstalls(): Boolean

    fun launchUnknownAppSettings()

    fun contentUri(apk: File): String

    fun launchUserConfirmedInstall(contentUri: String)
}

class InstallLauncher(private val platform: InstallPlatform) {
    fun launch(apk: File): InstallResult {
        if (!platform.canRequestPackageInstalls()) {
            platform.launchUnknownAppSettings()
            return InstallResult.PERMISSION_REQUIRED
        }
        platform.launchUserConfirmedInstall(platform.contentUri(apk))
        return InstallResult.CONFIRMATION_LAUNCHED
    }
}

class AndroidInstallPlatform(private val context: Context) : InstallPlatform {
    override fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun launchUnknownAppSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun contentUri(apk: File): String = FileProvider
        .getUriForFile(context, "${context.packageName}.updates", apk)
        .toString()

    @Suppress("DEPRECATION")
    override fun launchUserConfirmedInstall(contentUri: String) {
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(contentUri.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
