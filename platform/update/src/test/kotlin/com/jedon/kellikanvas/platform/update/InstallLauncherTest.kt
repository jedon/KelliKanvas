package com.jedon.kellikanvas.platform.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class InstallLauncherTest {
    @Test
    fun `routes to unknown app settings when permission is absent`() {
        val platform = RecordingInstallPlatform(canInstall = false)
        val result = InstallLauncher(platform).launch(File("release.apk"))
        assertThat(result).isEqualTo(InstallResult.PERMISSION_REQUIRED)
        assertThat(platform.settingsLaunched).isTrue()
        assertThat(platform.installedUri).isNull()
    }

    @Test
    fun `launches user confirmed package installer when permission exists`() {
        val platform = RecordingInstallPlatform(canInstall = true)
        val result = InstallLauncher(platform).launch(File("release.apk"))
        assertThat(result).isEqualTo(InstallResult.CONFIRMATION_LAUNCHED)
        assertThat(platform.installedUri).isEqualTo("content://updates/release.apk")
        assertThat(platform.silentInstallAttempted).isFalse()
    }

    private class RecordingInstallPlatform(private val canInstall: Boolean) : InstallPlatform {
        var settingsLaunched = false
        var installedUri: String? = null
        var silentInstallAttempted = false

        override fun canRequestPackageInstalls() = canInstall

        override fun launchUnknownAppSettings() {
            settingsLaunched = true
        }

        override fun contentUri(apk: File) = "content://updates/${apk.name}"

        override fun launchUserConfirmedInstall(contentUri: String) {
            installedUri = contentUri
        }
    }
}
