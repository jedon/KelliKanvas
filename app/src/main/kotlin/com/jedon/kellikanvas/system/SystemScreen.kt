package com.jedon.kellikanvas.system

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jedon.kellikanvas.BuildConfig
import com.jedon.kellikanvas.feature.settings.SettingsActionRow
import com.jedon.kellikanvas.feature.settings.SettingsReadOnlyRow
import com.jedon.kellikanvas.feature.settings.SettingsScreenScaffold
import com.jedon.kellikanvas.feature.settings.SettingsSectionHeader
import com.jedon.kellikanvas.feature.settings.UpdateCheckController
import com.jedon.kellikanvas.feature.settings.UpdateCheckUiState
import com.jedon.kellikanvas.feature.settings.updateCheckStatusLabel
import com.jedon.kellikanvas.permission.PermissionCoordinator
import com.jedon.kellikanvas.ui.tv.isTelevisionUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * System screen: app/OS versions, the update check (moved from Ambient settings),
 * the install-unknown-apps grant, and an always-visible permission status list.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun SystemScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    updateCheckController: UpdateCheckController? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coordinator = remember(context) { PermissionCoordinator(context) }

    // Statuses are recomputed when the user returns from system Settings (ON_RESUME);
    // no manual refresh button by design.
    var refreshToken by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshToken++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val installGranted = remember(refreshToken) {
        context.packageManager.canRequestPackageInstalls()
    }
    val permissionRows = remember(refreshToken) {
        permissionStatusRows(
            snapshot = coordinator.snapshot(),
            safReadGrantCount = context.contentResolver.persistedUriPermissions
                .count { it.isReadPermission },
        )
    }

    val idleUpdateState = remember { MutableStateFlow(UpdateCheckUiState.Idle) }
    val updateState by (updateCheckController?.state ?: idleUpdateState).collectAsState()

    SettingsScreenScaffold(
        title = "System",
        onBack = onBack,
        modifier = modifier,
    ) {
        item {
            SettingsSectionHeader(title = "About")
        }
        item {
            SettingsReadOnlyRow(
                label = "App version",
                valueLabel = formatVersionLabel(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE.toLong(),
                ),
            )
        }
        item {
            SettingsReadOnlyRow(
                label = "Android version",
                valueLabel = formatAndroidVersionLabel(
                    release = Build.VERSION.RELEASE,
                    sdkInt = Build.VERSION.SDK_INT,
                ),
            )
        }
        item {
            SettingsReadOnlyRow(
                label = "Device",
                valueLabel = formFactorLabel(context.isTelevisionUi()),
            )
        }
        item {
            SettingsSectionHeader(title = "Updates")
        }
        item {
            if (updateCheckController == null) {
                SettingsReadOnlyRow(
                    label = "App updates",
                    valueLabel = "Disabled in this build",
                    supportingText =
                    "Updates are disabled in this build " +
                        "(no release metadata key was configured at build time).",
                )
            } else {
                val busy =
                    updateState is UpdateCheckUiState.Checking ||
                        updateState is UpdateCheckUiState.Downloading
                SettingsActionRow(
                    label = "App updates",
                    buttonLabel = "Check for updates",
                    onClick = {
                        scope.launch { updateCheckController.checkForUpdates() }
                    },
                    enabled = !busy,
                    supportingText = updateCheckStatusLabel(updateState),
                )
            }
        }
        item {
            SettingsSectionHeader(title = "Install permission")
        }
        item {
            SettingsActionRow(
                label = "Install unknown apps",
                buttonLabel = "Open settings",
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            "package:${context.packageName}".toUri(),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                supportingText =
                "${installPermissionStatusLabel(installGranted)} · " +
                    "Required to install app updates.",
            )
        }
        item {
            SettingsSectionHeader(title = "Permissions")
        }
        permissionRows.forEach { row ->
            item {
                SettingsReadOnlyRow(
                    label = row.label,
                    valueLabel = row.status,
                )
            }
        }
        item {
            SettingsSectionHeader(title = "Diagnostics")
        }
        item {
            SettingsActionRow(
                label = "Diagnostics",
                buttonLabel = "Open diagnostics",
                onClick = onOpenDiagnostics,
                supportingText = "Connection status, bootstrap trace, and recent logs.",
            )
        }
    }
}
