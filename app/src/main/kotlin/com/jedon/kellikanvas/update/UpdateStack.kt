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
import java.net.Proxy
import java.net.URI

fun createUpdateCheckController(
    context: Context,
    httpClient: OkHttpClient,
): UpdateCheckController {
    val appContext = context.applicationContext
    val packageManager = appContext.packageManager
    val baseClient =
        httpClient.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    val authenticator = pinnedManifestAuthenticator()
    val replayGuard = ReleaseReplayGuard(AndroidAuthenticatedReleaseStore(appContext))
    val timestampStore = AndroidCheckTimestampStore(appContext)
    val updateCacheDir = File(appContext.cacheDir, "updates")
    val installLauncher = InstallLauncher(AndroidInstallPlatform(appContext))
    val installedPackageReader = InstalledPackageReader(packageManager)
    val originPolicy = UpdateOriginPolicy.remoteHttps("kanvas.kelli.photo")

    return UpdateCheckController(
        checkManifest = { manual, installedVersionCode ->
            AuthenticatedManifestRepository(
                transport = OkHttpUpdateTransport(originPolicy = originPolicy, client = baseClient),
                authenticator = authenticator,
                replayGuard = replayGuard,
                timestampStore = timestampStore,
                originPolicy = originPolicy,
                controlUris = listOf(URI("https://kanvas.kelli.photo/updates/update-envelope.json")),
            ).check(manual, installedVersionCode)
        },
        downloadAndVerify = { manifest, installed ->
            UpdateRepository(
                transport = OkHttpUpdateTransport(originPolicy = originPolicy, client = baseClient),
                verifier = ApkVerifier(AndroidArchiveInspector(PackageManagerArchiveReader(packageManager))),
                updateCacheDir = updateCacheDir,
                originPolicy = originPolicy,
            ).downloadAndVerify(manifest, installed)
        },
        launchInstall = installLauncher::launch,
        readInstalled = installedPackageReader::read,
        canInstall = { packageManager.canRequestPackageInstalls() },
    )
}
