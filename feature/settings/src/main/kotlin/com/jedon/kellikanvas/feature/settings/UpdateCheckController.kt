package com.jedon.kellikanvas.feature.settings

import com.jedon.kellikanvas.logging.DiagLog
import com.jedon.kellikanvas.platform.update.InstallResult
import com.jedon.kellikanvas.platform.update.InstalledPackage
import com.jedon.kellikanvas.platform.update.UpdateManifest
import com.jedon.kellikanvas.platform.update.UpdateRejected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.IOException

sealed class UpdateCheckUiState {
    data object Idle : UpdateCheckUiState()

    data object Checking : UpdateCheckUiState()

    data object UpToDate : UpdateCheckUiState()

    data class UpdateAvailable(
        val versionName: String,
        val versionCode: Long,
    ) : UpdateCheckUiState()

    data object Downloading : UpdateCheckUiState()

    data class ReadyToInstall(
        val versionName: String,
    ) : UpdateCheckUiState()

    data class PermissionRequired(val versionName: String) : UpdateCheckUiState()

    data class Error(
        val message: String,
    ) : UpdateCheckUiState()
}

class UpdateCheckController(
    private val checkManifest: (manual: Boolean, installedVersionCode: Long) -> UpdateManifest?,
    private val downloadAndVerify: (UpdateManifest, InstalledPackage) -> File,
    private val launchInstall: (File) -> InstallResult,
    private val readInstalled: () -> InstalledPackage,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val canInstall: () -> Boolean = { true },
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<UpdateCheckUiState>(UpdateCheckUiState.Idle)
    val state: StateFlow<UpdateCheckUiState> = _state.asStateFlow()
    private var pendingInstall: Pair<File, String>? = null

    /** Return from Android's permission screen without downloading the APK again. */
    suspend fun resumePendingInstall() {
        if (_state.value !is UpdateCheckUiState.PermissionRequired || !canInstall()) return
        installPending()
    }

    private suspend fun installPending(): Boolean = mutex.withLock {
        val pending = pendingInstall ?: return@withLock false
        try {
            _state.value = withContext(dispatcher) {
                if (!pending.first.isFile) {
                    pendingInstall = null
                    return@withContext UpdateCheckUiState.Error("Downloaded update is no longer available. Check again.")
                }
                when (launchInstall(pending.first)) {
                    InstallResult.PERMISSION_REQUIRED -> UpdateCheckUiState.PermissionRequired(pending.second)
                    InstallResult.CONFIRMATION_LAUNCHED -> {
                        pendingInstall = null
                        UpdateCheckUiState.ReadyToInstall(pending.second)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _state.value = UpdateCheckUiState.Error("Could not open the installer. Check for updates to retry.")
            pendingInstall = null
        }
        true
    }

    suspend fun checkForUpdates() {
        if (installPending()) return
        mutex.withLock {
            when (_state.value) {
                UpdateCheckUiState.Checking,
                UpdateCheckUiState.Downloading,
                -> return
                else -> Unit
            }
            _state.value = UpdateCheckUiState.Checking
        }
        DiagLog.i(TAG, "Update check started")
        try {
            val result =
                withContext(dispatcher) {
                    val installed = readInstalled()
                    val manifest =
                        try {
                            checkManifest(true, installed.versionCode)
                        } catch (error: UpdateRejected) {
                            if (error.message == "update is not newer") {
                                return@withContext UpdateCheckUiState.UpToDate
                            }
                            throw error
                        } ?: return@withContext UpdateCheckUiState.UpToDate

                    _state.value =
                        UpdateCheckUiState.UpdateAvailable(manifest.versionName, manifest.versionCode)
                    yield()
                    _state.value = UpdateCheckUiState.Downloading
                    val apk = downloadAndVerify(manifest, installed)
                    pendingInstall = apk to manifest.versionName
                    when (launchInstall(apk)) {
                        InstallResult.PERMISSION_REQUIRED ->
                            UpdateCheckUiState.PermissionRequired(manifest.versionName)
                        InstallResult.CONFIRMATION_LAUNCHED ->
                            UpdateCheckUiState.ReadyToInstall(manifest.versionName).also { pendingInstall = null }
                    }
                }
            DiagLog.i(TAG, "Update check finished: $result")
            _state.value = result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = mapUpdateError(error)
            DiagLog.w(TAG, "Update check failed: $message", error)
            _state.value = UpdateCheckUiState.Error(message)
        }
    }

    /**
     * Startup auto-check: passes manual=false so the 24h UpdateCheckPolicy interval
     * gating applies, and stops at [UpdateCheckUiState.UpdateAvailable] instead of
     * downloading — the Home banner points the user at the System screen to install.
     * Failures are silent (state returns to Idle).
     */
    suspend fun checkForUpdatesOnStartup() {
        mutex.withLock {
            if (_state.value != UpdateCheckUiState.Idle) return
            _state.value = UpdateCheckUiState.Checking
        }
        DiagLog.i(TAG, "Startup update check started")
        try {
            val manifest =
                withContext(dispatcher) {
                    val installed = readInstalled()
                    try {
                        checkManifest(false, installed.versionCode)
                    } catch (error: UpdateRejected) {
                        if (error.message == "update is not newer") null else throw error
                    }
                }
            _state.value =
                if (manifest == null) {
                    UpdateCheckUiState.Idle
                } else {
                    UpdateCheckUiState.UpdateAvailable(manifest.versionName, manifest.versionCode)
                }
            DiagLog.i(TAG, "Startup update check finished: ${_state.value}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            DiagLog.w(TAG, "Startup update check failed: ${mapUpdateError(error)}", error)
            _state.value = UpdateCheckUiState.Idle
        }
    }

    companion object {
        private const val TAG = "UpdateCheckController"

        fun mapUpdateError(error: Throwable): String {
            val message = error.message.orEmpty()
            return when {
                error is UpdateRejected && message.contains("authentication", ignoreCase = true) ->
                    "Signature verification failed"
                error is UpdateRejected && message.contains("signature", ignoreCase = true) ->
                    "Signature verification failed"
                error is UpdateRejected && message.contains("origin", ignoreCase = true) ->
                    "Update origin rejected"
                error is UpdateRejected && message.contains("not newer", ignoreCase = true) ->
                    "Up to date"
                error is IOException || message.contains("unable to resolve", ignoreCase = true) ->
                    "Network error"
                error is UpdateRejected && message.isNotBlank() -> message
                else -> "Network error"
            }
        }
    }
}
