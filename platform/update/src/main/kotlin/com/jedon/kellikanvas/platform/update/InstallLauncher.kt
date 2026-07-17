package com.jedon.kellikanvas.platform.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File

enum class InstallResult {
    PERMISSION_REQUIRED,
    CONFIRMATION_LAUNCHED,
}

interface InstallPlatform {
    fun canRequestPackageInstalls(): Boolean

    fun launchUnknownAppSettings()

    fun stagePackageInstallerSession(apk: File)
}

class InstallLauncher(private val platform: InstallPlatform) {
    fun launch(apk: File): InstallResult {
        if (!platform.canRequestPackageInstalls()) {
            platform.launchUnknownAppSettings()
            return InstallResult.PERMISSION_REQUIRED
        }
        platform.stagePackageInstallerSession(apk)
        return InstallResult.CONFIRMATION_LAUNCHED
    }
}

class StagedPackageSession(
    private val installer: PackageInstaller,
    val sessionId: Int,
) {
    fun commit(statusReceiver: IntentSender) {
        installer.openSession(sessionId).use { it.commit(statusReceiver) }
    }

    fun abandon() {
        installer.abandonSession(sessionId)
    }
}

class AndroidPackageSessionStager(
    context: Context,
    private val packageName: String = UpdateLimits.PACKAGE_NAME,
) {
    private val installer = context.packageManager.packageInstaller

    fun stage(apk: File): StagedPackageSession {
        val parameters =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= 31) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
        val sessionId = installer.createSession(parameters)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("kellikanvas.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
            }
            return StagedPackageSession(installer, sessionId)
        } catch (error: Exception) {
            installer.abandonSession(sessionId)
            throw error
        }
    }
}

class AndroidInstallPlatform(
    private val context: Context,
    private val sessionStager: AndroidPackageSessionStager = AndroidPackageSessionStager(context),
) : InstallPlatform {
    override fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun launchUnknownAppSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun stagePackageInstallerSession(apk: File) {
        val staged = sessionStager.stage(apk)
        try {
            val callback =
                Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_STATUS)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, staged.sessionId)
            val flags = installerPendingIntentFlags(Build.VERSION.SDK_INT)
            staged.commit(
                PendingIntent.getBroadcast(context, staged.sessionId, callback, flags).intentSender,
            )
        } catch (error: Exception) {
            staged.abandon()
            throw error
        }
    }
}

internal fun installerPendingIntentFlags(sdkInt: Int): Int = PendingIntent.FLAG_UPDATE_CURRENT or
    if (sdkInt >= 31) PendingIntent.FLAG_MUTABLE else 0

class UpdateInstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putInt("session.$sessionId.status", status)
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirmation != null) context.startActivity(confirmation)
        }
    }

    companion object {
        const val ACTION_STATUS = "com.jedon.kellikanvas.platform.update.INSTALL_STATUS"
        private const val PREFERENCES = "kellikanvas-install-status"
    }
}
