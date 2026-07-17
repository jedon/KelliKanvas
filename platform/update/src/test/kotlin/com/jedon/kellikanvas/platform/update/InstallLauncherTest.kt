package com.jedon.kellikanvas.platform.update

import android.app.PendingIntent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class InstallLauncherTest {
    @Test
    fun `package installer callback is mutable only where system fill-in requires it`() {
        assertThat(installerPendingIntentFlags(30) and PendingIntent.FLAG_MUTABLE).isEqualTo(0)
        assertThat(installerPendingIntentFlags(31) and PendingIntent.FLAG_MUTABLE).isNotEqualTo(0)
        assertThat(installerPendingIntentFlags(35) and PendingIntent.FLAG_IMMUTABLE).isEqualTo(0)
    }

    @Test
    fun `routes to unknown app settings when permission is absent`() {
        val platform = RecordingInstallPlatform(canInstall = false)
        val result = InstallLauncher(platform).launch(File("release.apk"))
        assertThat(result).isEqualTo(InstallResult.PERMISSION_REQUIRED)
        assertThat(platform.settingsLaunched).isTrue()
        assertThat(platform.sessionApk).isNull()
    }

    @Test
    fun `launches user confirmed package installer when permission exists`() {
        val platform = RecordingInstallPlatform(canInstall = true)
        val result = InstallLauncher(platform).launch(File("release.apk"))
        assertThat(result).isEqualTo(InstallResult.CONFIRMATION_LAUNCHED)
        assertThat(platform.sessionApk).isEqualTo(File("release.apk"))
        assertThat(platform.silentInstallAttempted).isFalse()
    }

    private class RecordingInstallPlatform(private val canInstall: Boolean) : InstallPlatform {
        var settingsLaunched = false
        var sessionApk: File? = null
        var silentInstallAttempted = false

        override fun canRequestPackageInstalls() = canInstall

        override fun launchUnknownAppSettings() {
            settingsLaunched = true
        }

        override fun stagePackageInstallerSession(apk: File) {
            sessionApk = apk
        }
    }
}
