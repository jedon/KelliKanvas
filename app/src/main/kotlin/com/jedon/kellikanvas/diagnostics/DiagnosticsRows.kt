package com.jedon.kellikanvas.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jedon.kellikanvas.feature.settings.SettingsReadOnlyRow

/** Informational rows remain remote-focusable so long diagnostic lists can scroll. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun DiagnosticsRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) = SettingsReadOnlyRow(label, value, modifier, supportingText)
