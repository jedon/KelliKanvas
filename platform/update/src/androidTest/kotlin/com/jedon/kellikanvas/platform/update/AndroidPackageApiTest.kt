package com.jedon.kellikanvas.platform.update

import android.content.Context
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidPackageApiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun generated_signed_target_apk_has_real_package_version_and_signer() {
        val installed = InstalledPackageReader(context.packageManager).read(context.packageName)
        val sourceApk = File(context.applicationInfo.sourceDir)
        val archive = PackageManagerArchiveReader(context.packageManager).readArchive(sourceApk)

        assertThat(archive.packageName).isEqualTo(installed.packageName)
        assertThat(archive.versionCode).isEqualTo(installed.versionCode)
        assertThat(archive.signerSha256).isNotEmpty()
        assertThat(archive.signerSha256).isEqualTo(installed.signerSha256)
    }

    @Test
    fun file_provider_exposes_only_update_cache_with_explicit_grants() {
        val update = File(context.cacheDir, "updates/instrumented.apk")
        update.parentFile?.mkdirs()
        update.writeBytes(byteArrayOf(1))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", update)
        assertThat(uri.scheme).isEqualTo("content")
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.updates", 0)
        assertThat(provider).isNotNull()
        assertThat(provider!!.exported).isFalse()
        assertThat(provider.grantUriPermissions).isTrue()
        context.contentResolver.openInputStream(uri).use { stream ->
            assertThat(stream!!.readBytes()).isEqualTo(byteArrayOf(1))
        }
        val privateFile = File(context.cacheDir, "private-fixture.txt")
        privateFile.writeText("fixture only")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.updates", privateFile)
            }
        } finally {
            privateFile.delete()
            update.delete()
        }
    }

    @Test
    fun production_stager_writes_generated_signed_apk_and_abandons_session() {
        val sourceApk = File(context.applicationInfo.sourceDir)
        val installer = context.packageManager.packageInstaller
        val staged = AndroidPackageSessionStager(context, context.packageName).stage(sourceApk)
        try {
            val session = installer.getSessionInfo(staged.sessionId)
            assertThat(session).isNotNull()
            assertThat(session?.appPackageName).isEqualTo(context.packageName)
            installer.openSession(staged.sessionId).use { openSession ->
                openSession.openRead("kellikanvas.apk").use { stagedApk ->
                    assertThat(stagedApk.readBytes()).isEqualTo(sourceApk.readBytes())
                }
            }
        } finally {
            staged.abandon()
        }
        // PackageInstaller removes abandoned sessions asynchronously in system_server.
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (installer.getSessionInfo(staged.sessionId) != null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
        }
        assertThat(installer.getSessionInfo(staged.sessionId)).isNull()
    }
}
