package com.jedon.kellikanvas.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jedon.kellikanvas.catalog.preferences.AppPreferencesState
import com.jedon.kellikanvas.model.AppPreferences

@Suppress("ktlint:standard:function-naming")
@Composable
fun PlaybackSettingsScreen(
    preferences: AppPreferencesState,
    onUpdatePreferences: (AppPreferences) -> Unit,
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
                    onUpdatePreferences(
                        withSlideDuration(app, app.slideDurationMillis - 1_000),
                    )
                },
                onIncrement = {
                    onUpdatePreferences(
                        withSlideDuration(app, app.slideDurationMillis + 1_000),
                    )
                },
                decrementEnabled = app.slideDurationMillis > 1_000,
            )
        }
        item {
            SettingsEnumRow(
                label = "Transition",
                valueLabel = formatEnumLabel(app.transitionType),
                onCycle = {
                    onUpdatePreferences(app.copy(transitionType = nextEnum(app.transitionType)))
                },
            )
        }
        item {
            SettingsStepperRow(
                label = "Transition duration",
                valueLabel = formatDurationLabel(app.transitionDurationMillis),
                onDecrement = {
                    onUpdatePreferences(
                        withTransitionDuration(app, app.transitionDurationMillis - 100),
                    )
                },
                onIncrement = {
                    onUpdatePreferences(
                        withTransitionDuration(app, app.transitionDurationMillis + 100),
                    )
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
                    onUpdatePreferences(app.copy(playbackOrder = nextEnum(app.playbackOrder)))
                },
            )
        }
        item {
            SettingsSwitchRow(
                label = "Loop",
                checked = app.loopEnabled,
                onCheckedChange = { onUpdatePreferences(app.copy(loopEnabled = it)) },
            )
        }
        item {
            SettingsSwitchRow(
                label = "Resume session",
                checked = app.resumeEnabled,
                onCheckedChange = { onUpdatePreferences(app.copy(resumeEnabled = it)) },
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
