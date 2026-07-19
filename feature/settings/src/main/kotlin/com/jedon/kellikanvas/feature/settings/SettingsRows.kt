package com.jedon.kellikanvas.feature.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.WideButton
import com.jedon.kellikanvas.ui.tv.isTelevisionUi
import androidx.compose.material3.MaterialTheme as PhoneMaterialTheme
import androidx.compose.material3.Text as PhoneText
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText

@Suppress("ktlint:standard:function-naming")
@Composable
fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    val television = LocalContext.current.isTelevisionUi()
    if (television) {
        TvText(
            text = title,
            style = TvMaterialTheme.typography.titleLarge,
            color = TvMaterialTheme.colorScheme.primary,
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 4.dp),
        )
    } else {
        PhoneText(
            text = title,
            style = PhoneMaterialTheme.typography.titleMedium,
            color = PhoneMaterialTheme.colorScheme.primary,
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun SettingsEnumRow(
    label: String,
    valueLabel: String,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    val television = LocalContext.current.isTelevisionUi()
    if (television) {
        val subtitle =
            buildString {
                append(valueLabel)
                append("  ·  OK to change")
                if (supportingText != null) {
                    append('\n')
                    append(supportingText)
                }
            }
        WideButton(
            onClick = onCycle,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            title = { TvText(text = label) },
            subtitle = { TvText(text = subtitle) },
        )
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhoneText(
                    text = label,
                    style = PhoneMaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onCycle, enabled = enabled) {
                    PhoneText(text = valueLabel)
                }
            }
            if (supportingText != null) {
                PhoneText(
                    text = supportingText,
                    style = PhoneMaterialTheme.typography.bodySmall,
                    color = PhoneMaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    val television = LocalContext.current.isTelevisionUi()
    if (television) {
        val stateLabel = if (checked) "On" else "Off"
        val subtitle =
            buildString {
                append(stateLabel)
                append("  ·  OK to toggle")
                if (supportingText != null) {
                    append('\n')
                    append(supportingText)
                }
            }
        WideButton(
            onClick = { onCheckedChange(!checked) },
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            title = { TvText(text = label) },
            subtitle = { TvText(text = subtitle) },
        )
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhoneText(
                    text = label,
                    style = PhoneMaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled,
                )
            }
            if (supportingText != null) {
                PhoneText(
                    text = supportingText,
                    style = PhoneMaterialTheme.typography.bodySmall,
                    color = PhoneMaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun SettingsStepperRow(
    label: String,
    valueLabel: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    decrementEnabled: Boolean = true,
    incrementEnabled: Boolean = true,
    supportingText: String? = null,
) {
    val television = LocalContext.current.isTelevisionUi()
    if (television) {
        val subtitle =
            buildString {
                append(valueLabel)
                append("  ·  ← / → or OK+")
                if (supportingText != null) {
                    append('\n')
                    append(supportingText)
                }
            }
        WideButton(
            onClick = {
                if (incrementEnabled) onIncrement()
            },
            enabled = decrementEnabled || incrementEnabled,
            modifier = modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action != KeyEvent.ACTION_DOWN || native.repeatCount != 0) {
                        return@onPreviewKeyEvent false
                    }
                    when (native.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (decrementEnabled) {
                                onDecrement()
                                true
                            } else {
                                true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (incrementEnabled) {
                                onIncrement()
                                true
                            } else {
                                true
                            }
                        }
                        else -> false
                    }
                },
            title = { TvText(text = label) },
            subtitle = { TvText(text = subtitle) },
        )
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PhoneText(text = label, style = PhoneMaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDecrement, enabled = decrementEnabled) {
                    PhoneText(text = "−")
                }
                PhoneText(
                    text = valueLabel,
                    style = PhoneMaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onIncrement, enabled = incrementEnabled) {
                    PhoneText(text = "+")
                }
            }
            if (supportingText != null) {
                PhoneText(
                    text = supportingText,
                    style = PhoneMaterialTheme.typography.bodySmall,
                    color = PhoneMaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun SettingsActionRow(
    label: String,
    buttonLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    val television = LocalContext.current.isTelevisionUi()
    if (television) {
        val subtitle =
            buildString {
                append(buttonLabel)
                if (supportingText != null) {
                    append('\n')
                    append(supportingText)
                }
            }
        WideButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            title = { TvText(text = label) },
            subtitle = { TvText(text = subtitle) },
        )
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhoneText(
                    text = label,
                    style = PhoneMaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onClick, enabled = enabled) {
                    PhoneText(text = buttonLabel)
                }
            }
            if (supportingText != null) {
                PhoneText(
                    text = supportingText,
                    style = PhoneMaterialTheme.typography.bodySmall,
                    color = PhoneMaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun SettingsReadOnlyRow(
    label: String,
    valueLabel: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    val television = LocalContext.current.isTelevisionUi()
    if (television) {
        val subtitle =
            buildString {
                append(valueLabel)
                if (supportingText != null) {
                    append('\n')
                    append(supportingText)
                }
            }
        WideButton(
            onClick = {},
            enabled = false,
            modifier = modifier.fillMaxWidth(),
            title = { TvText(text = label) },
            subtitle = { TvText(text = subtitle) },
        )
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhoneText(
                    text = label,
                    style = PhoneMaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                PhoneText(
                    text = valueLabel,
                    style = PhoneMaterialTheme.typography.bodyLarge,
                )
            }
            if (supportingText != null) {
                PhoneText(
                    text = supportingText,
                    style = PhoneMaterialTheme.typography.bodySmall,
                    color = PhoneMaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
