package com.jedon.kellikanvas.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jedon.kellikanvas.catalog.preferences.AppPreferencesState
import com.jedon.kellikanvas.model.AppPreferences
import com.jedon.kellikanvas.platform.ambient.AmbientCapabilities
import com.jedon.kellikanvas.platform.ambient.AndroidSensorInventory

@Suppress("ktlint:standard:function-naming")
@Composable
fun AmbientSettingsScreen(
    preferences: AppPreferencesState,
    onUpdatePreferences: ((AppPreferences) -> AppPreferences) -> Unit,
    onUpdateReducedMotion: ((Boolean) -> Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    capabilities: AmbientCapabilities? = null,
) {
    val context = LocalContext.current
    val resolvedCapabilities = capabilities ?: remember(context) {
        AndroidSensorInventory(context).inventory().capabilities
    }
    val app = preferences.appPreferences
    val lightAvailable = isAmbientSensorModeEnabled(resolvedCapabilities.light)
    val presenceEnabled = isPresenceToggleEnabled(resolvedCapabilities.presence)

    SettingsScreenScaffold(
        title = "Ambient and System",
        onBack = onBack,
        modifier = modifier,
    ) {
        item {
            SettingsSectionHeader(title = "Brightness")
        }
        item {
            SettingsEnumRow(
                label = "Brightness mode",
                valueLabel = formatEnumLabel(app.brightnessMode),
                onCycle = {
                    onUpdatePreferences { current ->
                        current.copy(
                            brightnessMode = nextAllowedBrightnessMode(
                                current = current.brightnessMode,
                                lightAvailable = lightAvailable,
                            ),
                        )
                    }
                },
                supportingText = brightnessModeSupportText(
                    mode = app.brightnessMode,
                    light = resolvedCapabilities.light,
                ),
            )
        }
        item {
            SettingsSectionHeader(title = "Presence")
        }
        item {
            SettingsSwitchRow(
                label = "Presence behavior",
                checked = app.presenceEnabled && presenceEnabled,
                onCheckedChange = { enabled ->
                    if (presenceEnabled) {
                        onUpdatePreferences { current ->
                            current.copy(presenceEnabled = enabled)
                        }
                    }
                },
                enabled = presenceEnabled,
                supportingText = if (presenceEnabled) {
                    null
                } else {
                    "CanvasTV presence data is unavailable to third-party apps."
                },
            )
        }
        item {
            SettingsSectionHeader(title = "Accessibility")
        }
        item {
            SettingsSwitchRow(
                label = "Reduced motion",
                checked = preferences.reducedMotion,
                onCheckedChange = { enabled ->
                    onUpdateReducedMotion { enabled }
                },
            )
        }
    }
}
