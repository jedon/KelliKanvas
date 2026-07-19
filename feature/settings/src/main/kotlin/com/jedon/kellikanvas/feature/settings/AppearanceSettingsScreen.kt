package com.jedon.kellikanvas.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jedon.kellikanvas.catalog.preferences.AppPreferencesState
import com.jedon.kellikanvas.model.AppPreferences

@Suppress("ktlint:standard:function-naming")
@Composable
fun AppearanceSettingsScreen(
    preferences: AppPreferencesState,
    onUpdatePreferences: ((AppPreferences) -> AppPreferences) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = preferences.appPreferences
    SettingsScreenScaffold(
        title = "Appearance",
        onBack = onBack,
        modifier = modifier,
    ) {
        item {
            SettingsSectionHeader(title = "Layouts")
        }
        item {
            SettingsEnumRow(
                label = "Landscape layout",
                valueLabel = formatEnumLabel(app.landscapeLayout),
                onCycle = {
                    onUpdatePreferences { current ->
                        current.copy(landscapeLayout = nextEnum(current.landscapeLayout))
                    }
                },
            )
        }
        item {
            SettingsEnumRow(
                label = "Single portrait layout",
                valueLabel = formatEnumLabel(app.singlePortraitLayout),
                onCycle = {
                    onUpdatePreferences { current ->
                        current.copy(singlePortraitLayout = nextEnum(current.singlePortraitLayout))
                    }
                },
            )
        }
        item {
            SettingsEnumRow(
                label = "Single portrait fit",
                valueLabel = formatEnumLabel(app.singlePortraitFit),
                onCycle = {
                    onUpdatePreferences { current ->
                        current.copy(singlePortraitFit = nextEnum(current.singlePortraitFit))
                    }
                },
            )
        }
        item {
            SettingsSectionHeader(title = "Portrait pairing")
        }
        item {
            SettingsEnumRow(
                label = "Portrait pairing",
                valueLabel = formatEnumLabel(app.portraitPairingMode),
                onCycle = {
                    onUpdatePreferences { current ->
                        current.copy(portraitPairingMode = nextEnum(current.portraitPairingMode))
                    }
                },
            )
        }
        item {
            SettingsStepperRow(
                label = "Look ahead",
                valueLabel = "${app.portraitLookAhead}",
                onDecrement = {
                    onUpdatePreferences { current ->
                        current.copy(
                            portraitLookAhead = clampPortraitLookAhead(current.portraitLookAhead - 1),
                        )
                    }
                },
                onIncrement = {
                    onUpdatePreferences { current ->
                        current.copy(
                            portraitLookAhead = clampPortraitLookAhead(current.portraitLookAhead + 1),
                        )
                    }
                },
                decrementEnabled = app.portraitLookAhead > 1,
                incrementEnabled = app.portraitLookAhead < 4,
            )
        }
        item {
            SettingsStepperRow(
                label = "Pair gutter",
                valueLabel = "${app.pairGutterDp} dp",
                onDecrement = {
                    onUpdatePreferences { current ->
                        current.copy(pairGutterDp = clampPairGutter(current.pairGutterDp - 4))
                    }
                },
                onIncrement = {
                    onUpdatePreferences { current ->
                        current.copy(pairGutterDp = clampPairGutter(current.pairGutterDp + 4))
                    }
                },
                decrementEnabled = app.pairGutterDp > 0,
            )
        }
        item {
            SettingsSectionHeader(title = "Blur")
        }
        item {
            SettingsEnumRow(
                label = "Blur strength",
                valueLabel = formatEnumLabel(app.blurStrength),
                onCycle = {
                    onUpdatePreferences { current ->
                        current.copy(blurStrength = nextEnum(current.blurStrength))
                    }
                },
            )
        }
        item {
            SettingsStepperRow(
                label = "Blur dimming",
                valueLabel = formatBlurDimLabel(app.blurDimAmount),
                onDecrement = {
                    onUpdatePreferences { current ->
                        current.copy(blurDimAmount = stepBlurDim(current.blurDimAmount, -1))
                    }
                },
                onIncrement = {
                    onUpdatePreferences { current ->
                        current.copy(blurDimAmount = stepBlurDim(current.blurDimAmount, 1))
                    }
                },
                decrementEnabled = app.blurDimAmount > 0.0,
                incrementEnabled = app.blurDimAmount < 1.0,
            )
        }
        item {
            SettingsSectionHeader(title = "Overlays")
        }
        item {
            SettingsSwitchRow(
                label = "Metadata overlay",
                checked = app.metadataOverlayEnabled,
                onCheckedChange = { enabled ->
                    onUpdatePreferences { current ->
                        current.copy(metadataOverlayEnabled = enabled)
                    }
                },
            )
        }
        item {
            SettingsSwitchRow(
                label = "Clock overlay",
                checked = app.clockOverlayEnabled,
                onCheckedChange = { enabled ->
                    onUpdatePreferences { current ->
                        current.copy(clockOverlayEnabled = enabled)
                    }
                },
            )
        }
        item {
            SettingsSwitchRow(
                label = "Capture date overlay",
                checked = app.captureDateOverlayEnabled,
                onCheckedChange = { enabled ->
                    onUpdatePreferences { current ->
                        current.copy(captureDateOverlayEnabled = enabled)
                    }
                },
            )
        }
        item {
            SettingsSwitchRow(
                label = "Filename overlay",
                checked = app.filenameOverlayEnabled,
                onCheckedChange = { enabled ->
                    onUpdatePreferences { current ->
                        current.copy(filenameOverlayEnabled = enabled)
                    }
                },
            )
        }
    }
}
