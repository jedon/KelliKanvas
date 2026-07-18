package com.jedon.kellikanvas.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jedon.kellikanvas.catalog.preferences.AppPreferencesState
import com.jedon.kellikanvas.model.AppPreferences

@Suppress("ktlint:standard:function-naming")
@Composable
fun AppearanceSettingsScreen(
    preferences: AppPreferencesState,
    onUpdatePreferences: (AppPreferences) -> Unit,
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
                    onUpdatePreferences(app.copy(landscapeLayout = nextEnum(app.landscapeLayout)))
                },
            )
        }
        item {
            SettingsEnumRow(
                label = "Single portrait layout",
                valueLabel = formatEnumLabel(app.singlePortraitLayout),
                onCycle = {
                    onUpdatePreferences(
                        app.copy(singlePortraitLayout = nextEnum(app.singlePortraitLayout)),
                    )
                },
            )
        }
        item {
            SettingsEnumRow(
                label = "Single portrait fit",
                valueLabel = formatEnumLabel(app.singlePortraitFit),
                onCycle = {
                    onUpdatePreferences(
                        app.copy(singlePortraitFit = nextEnum(app.singlePortraitFit)),
                    )
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
                    onUpdatePreferences(
                        app.copy(portraitPairingMode = nextEnum(app.portraitPairingMode)),
                    )
                },
            )
        }
        item {
            SettingsStepperRow(
                label = "Look ahead",
                valueLabel = "${app.portraitLookAhead}",
                onDecrement = {
                    onUpdatePreferences(
                        app.copy(
                            portraitLookAhead = clampPortraitLookAhead(app.portraitLookAhead - 1),
                        ),
                    )
                },
                onIncrement = {
                    onUpdatePreferences(
                        app.copy(
                            portraitLookAhead = clampPortraitLookAhead(app.portraitLookAhead + 1),
                        ),
                    )
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
                    onUpdatePreferences(
                        app.copy(pairGutterDp = clampPairGutter(app.pairGutterDp - 4)),
                    )
                },
                onIncrement = {
                    onUpdatePreferences(
                        app.copy(pairGutterDp = clampPairGutter(app.pairGutterDp + 4)),
                    )
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
                    onUpdatePreferences(app.copy(blurStrength = nextEnum(app.blurStrength)))
                },
            )
        }
        item {
            SettingsStepperRow(
                label = "Blur dimming",
                valueLabel = formatBlurDimLabel(app.blurDimAmount),
                onDecrement = {
                    onUpdatePreferences(
                        app.copy(
                            blurDimAmount = clampBlurDim(
                                ((app.blurDimAmount * 20).toInt() - 1) / 20.0,
                            ),
                        ),
                    )
                },
                onIncrement = {
                    onUpdatePreferences(
                        app.copy(
                            blurDimAmount = clampBlurDim(
                                ((app.blurDimAmount * 20).toInt() + 1) / 20.0,
                            ),
                        ),
                    )
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
                onCheckedChange = {
                    onUpdatePreferences(app.copy(metadataOverlayEnabled = it))
                },
            )
        }
        item {
            SettingsSwitchRow(
                label = "Clock overlay",
                checked = app.clockOverlayEnabled,
                onCheckedChange = {
                    onUpdatePreferences(app.copy(clockOverlayEnabled = it))
                },
            )
        }
        item {
            SettingsSwitchRow(
                label = "Capture date overlay",
                checked = app.captureDateOverlayEnabled,
                onCheckedChange = {
                    onUpdatePreferences(app.copy(captureDateOverlayEnabled = it))
                },
            )
        }
        item {
            SettingsSwitchRow(
                label = "Filename overlay",
                checked = app.filenameOverlayEnabled,
                onCheckedChange = {
                    onUpdatePreferences(app.copy(filenameOverlayEnabled = it))
                },
            )
        }
    }
}
