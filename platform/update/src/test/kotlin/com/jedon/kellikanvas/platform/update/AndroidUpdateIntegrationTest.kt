package com.jedon.kellikanvas.platform.update

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 31, 35])
class AndroidUpdateIntegrationTest {
    @Test
    fun `authenticated release state persists across store instances`() {
        val context = RuntimeEnvironment.getApplication()
        val first = AndroidAuthenticatedReleaseStore(context)
        first.save(AuthenticatedRelease(12, 34, "payload-hash"))
        assertThat(AndroidAuthenticatedReleaseStore(context).highest())
            .isEqualTo(AuthenticatedRelease(12, 34, "payload-hash"))
    }

    @Test
    fun `file provider is private and grants only explicit read access`() {
        val context = RuntimeEnvironment.getApplication()
        val apk = File(context.cacheDir, "updates/release.apk").apply {
            parentFile?.mkdirs()
            writeText("apk")
        }
        val authority = "${context.packageName}.updates"
        assertThat(apk.canonicalPath).startsWith(File(context.cacheDir, "updates").canonicalPath)
        val uri = Uri.parse("content://$authority/updates/release.apk")
        val provider = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)
        val intent = Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        assertThat(provider).isNotNull()
        assertThat(provider!!.exported).isFalse()
        assertThat(provider.grantUriPermissions).isTrue()
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
    }

    @Test
    fun `installer completion receiver is private and persists lifecycle status`() {
        val context = RuntimeEnvironment.getApplication()
        val receiver =
            context.packageManager.getReceiverInfo(
                android.content.ComponentName(context, UpdateInstallReceiver::class.java),
                0,
            )
        assertThat(receiver.exported).isFalse()
        UpdateInstallReceiver().onReceive(
            context,
            Intent(UpdateInstallReceiver.ACTION_STATUS)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, 7)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS),
        )
        assertThat(
            context.getSharedPreferences("kellikanvas-install-status", 0)
                .getInt("session.7.status", Int.MIN_VALUE),
        ).isEqualTo(PackageInstaller.STATUS_SUCCESS)
    }

    @Test
    fun `package manager readers preserve package version and signing history`() {
        val context = RuntimeEnvironment.getApplication()
        val current = Signature(byteArrayOf(1, 2, 3))
        val old = Signature(byteArrayOf(4, 5, 6))
        val signingInfo = Shadow.newInstanceOf(SigningInfo::class.java)
        shadowOf(signingInfo).setSignatures(arrayOf(current))
        shadowOf(signingInfo).setPastSigningCertificates(arrayOf(old, current))
        val packageInfo =
            PackageInfo().apply {
                packageName = UpdateLimits.PACKAGE_NAME
                longVersionCode = 42
                this.signingInfo = signingInfo
            }
        val apk = File(context.cacheDir, "archive.apk")
        shadowOf(context.packageManager).setPackageArchiveInfo(apk.absolutePath, packageInfo)
        shadowOf(context.packageManager).installPackage(packageInfo)

        val archive = PackageManagerArchiveReader(context.packageManager).readArchive(apk)
        val installed = InstalledPackageReader(context.packageManager).read()

        assertThat(archive.packageName).isEqualTo(UpdateLimits.PACKAGE_NAME)
        assertThat(archive.versionCode).isEqualTo(42)
        assertThat(archive.signerSha256).hasSize(2)
        assertThat(installed.signerSha256).isEqualTo(archive.signerSha256)
    }
}
