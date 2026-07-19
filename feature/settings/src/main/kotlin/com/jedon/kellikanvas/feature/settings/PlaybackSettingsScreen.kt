package com.jedon.kellikanvas.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jedon.kellikanvas.catalog.preferences.AppPreferencesState
import com.jedon.kellikanvas.model.AppPreferences

@Suppress("ktlint:standard:function-naming")
@Composable
fun PlaybackSettingsScreen(
    preferences: AppPreferencesState,
    onUpdatePreferences: ((AppPreferences) -> AppPreferences) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = preferences.appPreferences
    SettingsScreenScaffold(
        title = "Playback",
        onBack = onBack,
        modifier = modifier,
    ) {
        item {
            SettingsSectionHeader(title = "Timing")
        }
        item {
            SettingsStepperRow(
                label = "Slide duration",
                valueLabel = formatDurationLabel(app.slideDurationMillis),
                onDecrement = {
                    onUpdatePreferences { current ->
                        withSlideDuration(current, current.slideDurationMillis - 1_000)
                    }
                },
                onIncrement = {
                    onUpdatePreferences { current ->
                        withSlideDuration(current, current.slideDurationMillis + 1_000)
                    }
                },
                decrementEnabled = app.slideDurationMillis > 1_000,
            )
        }
        item {
            SettingsEnumRow(
                label = "Transition",
                valueLabel = formatEnumLabel(app.transitionType),
                onCycle = {
                    onUpdatePreferences { current ->
                        current.copy(transitionType = nextEnum(current.transitionType))
                    }
                },
            )
        }
        item {
            SettingsStepperRow(
                label = "Transition duration",
                valueLabel = formatDurationLabel(app.transitionDurationMillis),
                onDecrement = {
                    onUpdatePreferences { current ->
                        withTransitionDuration(current, current.transitionDurationMillis - 100)
                    }
                },
                onIncrement = {
                    onUpdatePreferences { current ->
                        withTransitionDuration(current, current.transitionDurationMillis + 100)
                    }
                },
                decrementEnabled = app.transitionDurationMillis > 0,
                incrementEnabled = app.transitionDurationMillis < app.slideDurationMillis - 1,
            )
        }
        item {
            SettingsSectionHeader(title = "Order and session")
        }
        item {
            SettingsEnumRow(
                label = "Order",
                valueLabel = formatEnumLabel(app.playbackOrder),
                onCycle = {
                    onUpdatePreferences { current ->
                        current.copy(playbackOrder = nextEnum(current.playbackOrder))
                    }
                },
            )
        }
        item {
            SettingsSwitchRow(
                label = "Loop",
                checked = app.loopEnabled,
                onCheckedChange = { enabled ->
                    onUpdatePreferences { current -> current.copy(loopEnabled = enabled) }
                },
            )
        }
        item {
            SettingsSwitchRow(
                label = "Resume session",
                checked = app.resumeEnabled,
                onCheckedChange = { enabled ->
                    onUpdatePreferences { current -> current.copy(resumeEnabled = enabled) }
                },
            )
        }
        item {
            SettingsReadOnlyRow(
                label = "New photos",
                valueLabel = formatEnumLabel(app.newPhotosPolicy),
                supportingText = "Only Next Cycle is available today.",
            )
        }
    }
}
