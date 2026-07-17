package com.jedon.kellikanvas.platform.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
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
    fun file_provider_grant_is_private_and_explicit() {
        val update = File(context.cacheDir, "updates/instrumented.apk")
        update.parentFile?.mkdirs()
        update.writeBytes(byteArrayOf(1))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", update)
        val instrumentationPackage = InstrumentationRegistry.getInstrumentation().context.packageName

        assertThat(uri.scheme).isEqualTo("content")
        val instrumentationUid = InstrumentationRegistry.getInstrumentation().context.applicationInfo.uid
        context.grantUriPermission(
            instrumentationPackage,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        assertThat(
            context.checkUriPermission(uri, -1, instrumentationUid, Intent.FLAG_GRANT_READ_URI_PERMISSION),
        ).isEqualTo(android.content.pm.PackageManager.PERMISSION_GRANTED)
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
        assertThat(installer.getSessionInfo(staged.sessionId)).isNull()
    }
}
