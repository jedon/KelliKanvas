package com.jedon.kellikanvas.feature.settings

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.platform.update.InstallResult
import com.jedon.kellikanvas.platform.update.InstalledPackage
import com.jedon.kellikanvas.platform.update.UpdateLimits
import com.jedon.kellikanvas.platform.update.UpdateManifest
import com.jedon.kellikanvas.platform.update.UpdateRejected
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.net.URI

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckControllerTest {
    private val installed =
        InstalledPackage(
            packageName = UpdateLimits.PACKAGE_NAME,
            versionCode = 7,
            signerSha256 = setOf("A".repeat(64)),
        )

    private val newerManifest =
        UpdateManifest(
            schema = 1,
            sequence = 8,
            packageName = UpdateLimits.PACKAGE_NAME,
            versionCode = 8,
            versionName = "1.0.7",
            apkUrl = URI("http://darklingnas:8088/kellikanvas-8.apk"),
            checksumUrl = URI("http://darklingnas:8088/kellikanvas-8.apk.sha256"),
            sizeBytes = 100,
            sha256 = "a".repeat(64),
            signerSha256 = "A".repeat(64),
        )

    @Test
    fun startsIdleAndReportsUpToDateWhenUpdateIsNotNewer() = runTest(UnconfinedTestDispatcher()) {
        val controller =
            UpdateCheckController(
                checkManifest = { _, _ -> throw UpdateRejected("update is not newer") },
                downloadAndVerify = { _, _ -> error("should not download") },
                launchInstall = { error("should not install") },
                readInstalled = { installed },
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        assertThat(controller.state.value).isEqualTo(UpdateCheckUiState.Idle)
        controller.checkForUpdates()

        assertThat(controller.state.value).isEqualTo(UpdateCheckUiState.UpToDate)
    }

    @Test
    fun downloadsVerifyAndLaunchesInstallWhenNewerUpdateIsAvailable() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val apk = File.createTempFile("kellikanvas", ".apk")
        var launched: File? = null
        val states = mutableListOf<UpdateCheckUiState>()
        val controller =
            UpdateCheckController(
                checkManifest = { manual, versionCode ->
                    assertThat(manual).isTrue()
                    assertThat(versionCode).isEqualTo(7)
                    newerManifest
                },
                downloadAndVerify = { manifest, packageInfo ->
                    assertThat(manifest).isEqualTo(newerManifest)
                    assertThat(packageInfo).isEqualTo(installed)
                    apk
                },
                launchInstall = { file ->
                    launched = file
                    InstallResult.CONFIRMATION_LAUNCHED
                },
                readInstalled = { installed },
                dispatcher = dispatcher,
            )
        backgroundScope.launch(dispatcher) {
            controller.state.collect { states.add(it) }
        }

        controller.checkForUpdates()

        assertThat(states).containsAtLeast(
            UpdateCheckUiState.Idle,
            UpdateCheckUiState.Checking,
            UpdateCheckUiState.UpdateAvailable("1.0.7", 8),
            UpdateCheckUiState.Downloading,
            UpdateCheckUiState.ReadyToInstall("1.0.7"),
        ).inOrder()
        assertThat(launched).isEqualTo(apk)
        assertThat(controller.state.value).isEqualTo(UpdateCheckUiState.ReadyToInstall("1.0.7"))
    }

    @Test
    fun mapsSignatureOriginAndNetworkFailuresToClearErrorStates() = runTest(UnconfinedTestDispatcher()) {
        assertErrorMessage(
            UpdateRejected("metadata authentication failed"),
            "Signature verification failed",
        )
        assertErrorMessage(
            UpdateRejected("update URL is outside the allowed origin"),
            "Update origin rejected",
        )
        assertErrorMessage(
            java.net.UnknownHostException("darklingnas"),
            "Network error",
        )
    }

    @Test
    fun reportsPermissionRequiredWhenInstallerCannotProceed() = runTest(UnconfinedTestDispatcher()) {
        val apk = File.createTempFile("kellikanvas", ".apk")
        val controller =
            UpdateCheckController(
                checkManifest = { _, _ -> newerManifest },
                downloadAndVerify = { _, _ -> apk },
                launchInstall = { InstallResult.PERMISSION_REQUIRED },
                readInstalled = { installed },
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        controller.checkForUpdates()

        assertThat(controller.state.value)
            .isEqualTo(UpdateCheckUiState.Error("Install permission required"))
    }

    @Test
    fun startupCheckIsNonManualAndStopsAtUpdateAvailableWithoutDownloading() = runTest(UnconfinedTestDispatcher()) {
        var manualSeen: Boolean? = null
        val controller =
            UpdateCheckController(
                checkManifest = { manual, versionCode ->
                    manualSeen = manual
                    assertThat(versionCode).isEqualTo(7)
                    newerManifest
                },
                downloadAndVerify = { _, _ -> error("startup check must not download") },
                launchInstall = { error("startup check must not install") },
                readInstalled = { installed },
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        controller.checkForUpdatesOnStartup()

        assertThat(manualSeen).isFalse()
        assertThat(controller.state.value)
            .isEqualTo(UpdateCheckUiState.UpdateAvailable("1.0.7", 8))
    }

    @Test
    fun startupCheckStaysIdleWhenPolicyGatedOrUpToDate() = runTest(UnconfinedTestDispatcher()) {
        val controller =
            UpdateCheckController(
                // Null is what the manifest repository returns when the 24h gate applies
                // or no newer version exists.
                checkManifest = { _, _ -> null },
                downloadAndVerify = { _, _ -> error("unused") },
                launchInstall = { error("unused") },
                readInstalled = { installed },
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        controller.checkForUpdatesOnStartup()

        assertThat(controller.state.value).isEqualTo(UpdateCheckUiState.Idle)
    }

    @Test
    fun startupCheckFailuresAreSilent() = runTest(UnconfinedTestDispatcher()) {
        val controller =
            UpdateCheckController(
                checkManifest = { _, _ -> throw java.net.UnknownHostException("darklingnas") },
                downloadAndVerify = { _, _ -> error("unused") },
                launchInstall = { error("unused") },
                readInstalled = { installed },
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            )

        controller.checkForUpdatesOnStartup()

        assertThat(controller.state.value).isEqualTo(UpdateCheckUiState.Idle)
    }

    private suspend fun assertErrorMessage(failure: Throwable, expected: String) {
        val controller =
            UpdateCheckController(
                checkManifest = { _, _ -> throw failure },
                downloadAndVerify = { _, _ -> error("unused") },
                launchInstall = { error("unused") },
                readInstalled = { installed },
                dispatcher = UnconfinedTestDispatcher(),
            )
        controller.checkForUpdates()
        assertThat(controller.state.value).isEqualTo(UpdateCheckUiState.Error(expected))
    }
}
