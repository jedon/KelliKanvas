package com.jedon.kellikanvas.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.WideButton
import androidx.tv.material3.WideButtonDefaults
import com.jedon.kellikanvas.ui.tv.HighContrastFocusDefaults
import com.jedon.kellikanvas.ui.tv.isTelevisionUi
import androidx.compose.material3.MaterialTheme as PhoneMaterialTheme
import androidx.compose.material3.Text as PhoneText
import androidx.tv.material3.Text as TvText

/**
 * Read-only diagnostics row. Unlike SettingsReadOnlyRow this stays focusable on TV
 * (enabled no-op button) so long diagnostics lists remain DPAD-scrollable, with the
 * shared high-contrast focus ring.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun DiagnosticsRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    val television = LocalContext.current.isTelevisionUi()
    if (television) {
        val subtitle =
            buildString {
                append(value)
                if (supportingText != null) {
                    append('\n')
                    append(supportingText)
                }
            }
        WideButton(
            onClick = {},
            border = WideButtonDefaults.border(
                focusedBorder = HighContrastFocusDefaults.TvFocusedBorder,
            ),
            modifier = modifier.fillMaxWidth(),
            title = { TvText(text = label) },
            subtitle = { TvText(text = subtitle) },
        )
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PhoneText(
                text = label,
                style = PhoneMaterialTheme.typography.titleSmall,
            )
            PhoneText(
                text = value,
                style = PhoneMaterialTheme.typography.bodyMedium,
            )
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
