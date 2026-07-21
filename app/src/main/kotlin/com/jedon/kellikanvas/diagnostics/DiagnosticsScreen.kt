package com.jedon.kellikanvas.diagnostics

import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jedon.kellikanvas.BuildConfig
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.feature.settings.SettingsActionRow
import com.jedon.kellikanvas.feature.settings.SettingsScreenScaffold
import com.jedon.kellikanvas.feature.settings.SettingsSectionHeader
import com.jedon.kellikanvas.feature.settings.UpdateCheckController
import com.jedon.kellikanvas.feature.settings.UpdateCheckUiState
import com.jedon.kellikanvas.feature.settings.updateCheckStatusLabel
import com.jedon.kellikanvas.logging.BootstrapTrace
import com.jedon.kellikanvas.logging.DiagLog
import com.jedon.kellikanvas.permission.PermissionCoordinator
import com.jedon.kellikanvas.platform.update.UpdateOriginTrace
import com.jedon.kellikanvas.source.nas.NasResolution
import com.jedon.kellikanvas.system.formFactorLabel
import com.jedon.kellikanvas.system.formatAndroidVersionLabel
import com.jedon.kellikanvas.system.formatVersionLabel
import com.jedon.kellikanvas.system.permissionStatusRows
import com.jedon.kellikanvas.ui.tv.isTelevisionUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Newest-first DiagLog entries shown before the list is cut off. */
private const val MAX_LOG_ENTRIES = 200

/**
 * Diagnostics screen: app/device info, permission + SAF grant status, source
 * restore status, last bootstrap trace, update state, NAS resolution, an
 * on-demand connectivity test, and recent DiagLog entries.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    restoreStatuses: List<RootRestoreStatus>,
    roots: List<SelectedRoot>,
    connectivityRunner: ConnectivityTestRunner,
    modifier: Modifier = Modifier,
    updateCheckController: UpdateCheckController? = null,
    lastUpdateCheckMillis: Long? = null,
    nasResolution: NasResolution? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val uptimeMillis = remember {
        SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
    }
    val persistedGrants = remember(context) {
        context.contentResolver.persistedUriPermissions.toList()
    }
    val permissionRows = remember(context, persistedGrants) {
        permissionStatusRows(
            snapshot = PermissionCoordinator(context).snapshot(),
            safReadGrantCount = persistedGrants.count { it.isReadPermission },
        )
    }
    val bootstrapRecord = remember { BootstrapTrace.last() }
    val updateOrigin = remember { UpdateOriginTrace.last() }
    val logEntries = remember {
        DiagLog.snapshot().asReversed().take(MAX_LOG_ENTRIES)
    }

    val idleUpdateState = remember { MutableStateFlow(UpdateCheckUiState.Idle) }
    val updateState by (updateCheckController?.state ?: idleUpdateState).collectAsState()
    val connectivityState by connectivityRunner.state.collectAsState()

    SettingsScreenScaffold(
        title = "Diagnostics",
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SettingsSectionHeader(title = "App & device") }
        item {
            DiagnosticsRow(
                label = "App version",
                value = formatVersionLabel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()),
            )
        }
        item {
            DiagnosticsRow(
                label = "Android version",
                value = formatAndroidVersionLabel(Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
            )
        }
        item {
            DiagnosticsRow(
                label = "Form factor",
                value = formFactorLabel(context.isTelevisionUi()),
            )
        }
        item {
            DiagnosticsRow(label = "Process uptime", value = formatUptime(uptimeMillis))
        }

        item { SettingsSectionHeader(title = "Permissions & SAF grants") }
        permissionRows.forEach { row ->
            item { DiagnosticsRow(label = row.label, value = row.status) }
        }
        if (persistedGrants.isEmpty()) {
            item { DiagnosticsRow(label = "Persisted SAF grants", value = "None") }
        } else {
            persistedGrants.forEach { grant ->
                item {
                    DiagnosticsRow(
                        label = "SAF grant",
                        value = grant.uri.toString(),
                        supportingText = listOfNotNull(
                            "read".takeIf { grant.isReadPermission },
                            "write".takeIf { grant.isWritePermission },
                        ).joinToString().ifEmpty { "no permissions" },
                    )
                }
            }
        }

        item { SettingsSectionHeader(title = "Sources & restore status") }
        if (restoreStatuses.isEmpty()) {
            item { DiagnosticsRow(label = "Configured sources", value = "None") }
        } else {
            restoreStatuses.forEach { status ->
                item {
                    val rootLabels = roots
                        .filter { it.profileId == status.profileId }
                        .map(SelectedRoot::displayLabel)
                    DiagnosticsRow(
                        label = "${status.label} (${status.kind?.name ?: "unknown"})",
                        value = restoreStatusValue(status),
                        supportingText = rootLabels
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = "Roots: "),
                    )
                }
            }
        }

        item { SettingsSectionHeader(title = "Last bootstrap") }
        if (bootstrapRecord == null) {
            item {
                DiagnosticsRow(
                    label = "Bootstrap trace",
                    value = "No bootstrap has run this session",
                )
            }
        } else {
            item {
                DiagnosticsRow(
                    label = "Started",
                    value = formatTimestamp(bootstrapRecord.startedAtMillis),
                )
            }
            bootstrapRecord.steps.forEach { step ->
                item { DiagnosticsRow(label = step.name, value = bootstrapStepValue(step)) }
            }
            item { DiagnosticsRow(label = "Result", value = bootstrapRecord.result) }
        }

        item { SettingsSectionHeader(title = "Updates") }
        item {
            DiagnosticsRow(label = "Last check", value = lastUpdateCheckLabel(lastUpdateCheckMillis))
        }
        item {
            DiagnosticsRow(
                label = "Status",
                value = if (updateCheckController == null) {
                    "Disabled in this build"
                } else {
                    updateCheckStatusLabel(updateState)
                },
            )
        }
        item {
            DiagnosticsRow(
                label = "Origin used",
                value = updateOriginLabel(updateOrigin?.uri?.host),
            )
        }

        item { SettingsSectionHeader(title = "NAS resolution") }
        item {
            DiagnosticsRow(
                label = "Last resolution",
                value = nasResolutionStatusLabel(nasResolution),
            )
        }

        item { SettingsSectionHeader(title = "Connectivity test") }
        item {
            SettingsActionRow(
                label = "Connectivity test",
                buttonLabel = if (connectivityState.running) "Running…" else "Run connectivity test",
                onClick = { scope.launch { connectivityRunner.run() } },
                enabled = !connectivityState.running,
                supportingText = "TCP, SMB, DLNA, and per-root listing checks.",
            )
        }
        connectivityState.results.forEach { result ->
            item {
                DiagnosticsRow(
                    label = result.name,
                    value = "${if (result.ok) "Pass" else "Fail"} — ${result.detail}",
                )
            }
        }

        item { SettingsSectionHeader(title = "Recent log (newest first)") }
        if (logEntries.isEmpty()) {
            item { DiagnosticsRow(label = "Log", value = "No entries yet") }
        } else {
            logEntries.forEach { entry ->
                item {
                    DiagnosticsRow(
                        label = diagLogRowTitle(entry),
                        value = diagLogRowValue(entry),
                    )
                }
            }
        }
    }
}
