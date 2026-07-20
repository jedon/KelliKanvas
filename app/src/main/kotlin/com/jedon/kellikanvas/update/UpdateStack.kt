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
    cachedNasIp: String? = null,
): UpdateCheckController {
    val appContext = context.applicationContext
    val packageManager = appContext.packageManager
    val originPolicy = UpdateOriginPolicy.qnapLan(cachedNasIp)
    val transport =
        OkHttpUpdateTransport(
            originPolicy = originPolicy,
            client = httpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )
    val authenticator = pinnedManifestAuthenticator()
    val manifestRepository =
        AuthenticatedManifestRepository(
            transport = transport,
            authenticator = authenticator,
            replayGuard = ReleaseReplayGuard(AndroidAuthenticatedReleaseStore(appContext)),
            timestampStore = AndroidCheckTimestampStore(appContext),
            originPolicy = originPolicy,
            controlUris = UpdateOriginPolicy.qnapControlUris(cachedNasIp),
        )
    val updateRepository =
        UpdateRepository(
            transport = transport,
            verifier = ApkVerifier(AndroidArchiveInspector(PackageManagerArchiveReader(packageManager))),
            updateCacheDir = File(appContext.cacheDir, "updates"),
            originPolicy = originPolicy,
        )
    val installLauncher = InstallLauncher(AndroidInstallPlatform(appContext))
    val installedPackageReader = InstalledPackageReader(packageManager)
    return UpdateCheckController(
        checkManifest = { manual, installedVersionCode ->
            manifestRepository.check(manual, installedVersionCode)
        },
        downloadAndVerify = updateRepository::downloadAndVerify,
        launchInstall = installLauncher::launch,
        readInstalled = installedPackageReader::read,
    )
}
