package com.jedon.kellikanvas.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jedon.kellikanvas.catalog.preferences.AppPreferencesState
import com.jedon.kellikanvas.model.AppPreferences
import com.jedon.kellikanvas.model.BrightnessMode
import com.jedon.kellikanvas.platform.ambient.AmbientCapabilities
import com.jedon.kellikanvas.platform.ambient.AndroidSensorInventory
import com.jedon.kellikanvas.platform.ambient.CapabilityStatus

@Suppress("ktlint:standard:function-naming")
@Composable
fun AmbientSettingsScreen(
    preferences: AppPreferencesState,
    onUpdatePreferences: (AppPreferences) -> Unit,
    onUpdateReducedMotion: (Boolean) -> Unit,
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
                    onUpdatePreferences(
                        app.copy(
                            brightnessMode = nextAllowedBrightnessMode(
                                current = app.brightnessMode,
                                lightAvailable = lightAvailable,
                            ),
                        ),
                    )
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
                        onUpdatePreferences(app.copy(presenceEnabled = enabled))
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
                onCheckedChange = onUpdateReducedMotion,
            )
        }
    }
}

internal fun nextAllowedBrightnessMode(
    current: BrightnessMode,
    lightAvailable: Boolean,
): BrightnessMode {
    var next = nextEnum(current)
    repeat(BrightnessMode.entries.size) {
        if (next != BrightnessMode.AMBIENT_SENSOR || lightAvailable) {
            return next
        }
        next = nextEnum(next)
    }
    return BrightnessMode.FOLLOW_TV
}

internal fun brightnessModeSupportText(
    mode: BrightnessMode,
    light: CapabilityStatus,
): String? = when {
    mode == BrightnessMode.AMBIENT_SENSOR && light != CapabilityStatus.AVAILABLE ->
        "No usable ambient-light sensor is available on this device."
    mode == BrightnessMode.SCHEDULE ->
        "Schedule times are coming later. Mode is saved now."
    light == CapabilityStatus.UNAVAILABLE ->
        "Ambient sensor mode is unavailable; Follow TV and Schedule remain selectable."
    else -> null
}
