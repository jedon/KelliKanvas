package com.jedon.kellikanvas.feature.settings

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jedon.kellikanvas.ui.tv.KanvasButton
import com.jedon.kellikanvas.ui.tv.KanvasColors
import com.jedon.kellikanvas.ui.tv.isTelevisionUi

@Suppress("ktlint:standard:function-naming")
@Composable
fun SettingsSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(),
        color = KanvasColors.Muted,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
        modifier = modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SettingsControlRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
    value: @Composable () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactions,
        color = if (focused) KanvasColors.Accent else KanvasColors.Surface,
        contentColor = if (focused) KanvasColors.OnAccent else KanvasColors.Text,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (focused) KanvasColors.Text else KanvasColors.Border),
        modifier = modifier.fillMaxWidth().heightIn(min = 68.dp),
    ) {
        BoxWithConstraints {
            if (maxWidth < 420.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    if (supportingText != null) {
                        Text(
                            supportingText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (focused) KanvasColors.OnAccent.copy(alpha = .8f) else KanvasColors.Muted,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { value() }
                }
            } else {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        if (supportingText != null) {
                            Text(
                                supportingText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (focused) KanvasColors.OnAccent.copy(alpha = .8f) else KanvasColors.Muted,
                            )
                        }
                    }
                    value()
                }
            }
        }
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
    SettingsControlRow(label, onCycle, modifier, enabled, supportingText) {
        Text("$valueLabel  ›", style = MaterialTheme.typography.bodyMedium)
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
    SettingsControlRow(label, { onCheckedChange(!checked) }, modifier, enabled, supportingText) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
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
    SettingsControlRow(
        label,
        onClick = { if (incrementEnabled) onIncrement() },
        modifier = modifier.onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action != KeyEvent.ACTION_DOWN || native.repeatCount != 0) return@onPreviewKeyEvent false
            when (stepperKeyAction(native.keyCode, decrementEnabled, incrementEnabled)) {
                StepperKeyAction.DECREMENT -> {
                    onDecrement()
                    true
                }
                StepperKeyAction.INCREMENT -> {
                    onIncrement()
                    true
                }
                StepperKeyAction.PASS -> false
            }
        },
        enabled = decrementEnabled || incrementEnabled,
        supportingText = supportingText,
    ) {
        if (television) {
            Text("‹  $valueLabel  ›", style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KanvasButton("−", onDecrement, enabled = decrementEnabled)
                Text(valueLabel, style = MaterialTheme.typography.bodyMedium)
                KanvasButton("+", onIncrement, enabled = incrementEnabled)
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
    SettingsControlRow(label, onClick, modifier, enabled, supportingText) {
        Text("$buttonLabel  ›", style = MaterialTheme.typography.bodyMedium)
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
    // Keep informational rows reachable by remote so long pages can scroll to the end.
    SettingsControlRow(label, {}, modifier, supportingText = supportingText) {
        Text(valueLabel, style = MaterialTheme.typography.bodyMedium)
    }
}
