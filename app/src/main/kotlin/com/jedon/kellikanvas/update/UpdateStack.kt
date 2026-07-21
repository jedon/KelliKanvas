package com.jedon.kellikanvas.update

import android.content.Context
import com.jedon.kellikanvas.feature.settings.UpdateCheckController
import com.jedon.kellikanvas.platform.update.AndroidArchiveInspector
import com.jedon.kellikanvas.platform.update.AndroidAuthenticatedReleaseStore
import com.jedon.kellikanvas.platform.update.AndroidCheckTimestampStore
import com.jedon.kellikanvas.platform.update.AndroidInstallPlatform
import com.jedon.kellikanvas.platform.update.ApkVerifier
import com.jedon.kellikanvas.platform.update.AuthenticatedManifestRepository
import com.jedon.kellikanvas.platform.update.InstallLauncher
import com.jedon.kellikanvas.platform.update.InstalledPackageReader
import com.jedon.kellikanvas.platform.update.OkHttpUpdateTransport
import com.jedon.kellikanvas.platform.update.PackageManagerArchiveReader
import com.jedon.kellikanvas.platform.update.ReleaseReplayGuard
import com.jedon.kellikanvas.platform.update.UpdateOriginPolicy
import com.jedon.kellikanvas.platform.update.UpdateRepository
import okhttp3.OkHttpClient
import java.io.File

fun createUpdateCheckController(
    context: Context,
    httpClient: OkHttpClient,
    cachedNasIp: () -> String? = { null },
): UpdateCheckController {
    val appContext = context.applicationContext
    val packageManager = appContext.packageManager
    val baseClient =
        httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    val authenticator = pinnedManifestAuthenticator()
    val replayGuard = ReleaseReplayGuard(AndroidAuthenticatedReleaseStore(appContext))
    val timestampStore = AndroidCheckTimestampStore(appContext)
    val updateCacheDir = File(appContext.cacheDir, "updates")
    val installLauncher = InstallLauncher(AndroidInstallPlatform(appContext))
    val installedPackageReader = InstalledPackageReader(packageManager)

    return UpdateCheckController(
        checkManifest = { manual, installedVersionCode ->
            val cached = cachedNasIp()
            AuthenticatedManifestRepository(
                transport = OkHttpUpdateTransport(originPolicy = UpdateOriginPolicy.qnapLan(cached), client = baseClient),
                authenticator = authenticator,
                replayGuard = replayGuard,
                timestampStore = timestampStore,
                originPolicy = UpdateOriginPolicy.qnapLan(cached),
                controlUris = UpdateOriginPolicy.qnapControlUris(cached),
            ).check(manual, installedVersionCode)
        },
        downloadAndVerify = { manifest, installed ->
            val cached = cachedNasIp()
            UpdateRepository(
                transport = OkHttpUpdateTransport(originPolicy = UpdateOriginPolicy.qnapLan(cached), client = baseClient),
                verifier = ApkVerifier(AndroidArchiveInspector(PackageManagerArchiveReader(packageManager))),
                updateCacheDir = updateCacheDir,
                originPolicy = UpdateOriginPolicy.qnapLan(cached),
            ).downloadAndVerify(manifest, installed)
        },
        launchInstall = installLauncher::launch,
        readInstalled = installedPackageReader::read,
    )
}
