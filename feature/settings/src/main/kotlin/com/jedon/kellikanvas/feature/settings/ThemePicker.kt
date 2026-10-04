package com.jedon.kellikanvas.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.model.AppTheme
import com.jedon.kellikanvas.ui.tv.KanvasBat
import com.jedon.kellikanvas.ui.tv.KanvasColors
import com.jedon.kellikanvas.ui.tv.kanvasResolvedPalette

fun themeName(theme: AppTheme): String = when (theme) {
    AppTheme.KELLI -> "Kelli"
    AppTheme.GALLERY -> "Gallery"
    AppTheme.MIDNIGHT -> "Midnight"
    AppTheme.PAPER -> "Paper"
    AppTheme.SYSTEM -> "Device colors"
}

private fun themeDescription(theme: AppTheme): String = when (theme) {
    AppTheme.KELLI -> "Purple, gold & black. A little after-dark magic."
    AppTheme.GALLERY -> "Soft sage and charcoal. Quiet and natural."
    AppTheme.MIDNIGHT -> "Deep blue and cool silver. Made for evenings."
    AppTheme.PAPER -> "Warm ivory and bronze. A lighter gallery."
    AppTheme.SYSTEM -> "Android colors, tuned for your device."
}

/** Explicit choices remain easy to compare and navigate with a TV remote. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ThemeChoice(theme: AppTheme, selected: Boolean, onSelect: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val palette = kanvasResolvedPalette(theme)
    Surface(
        onClick = onSelect,
        interactionSource = interactions,
        color = if (selected || focused) KanvasColors.Elevated else KanvasColors.Surface,
        contentColor = KanvasColors.Text,
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (selected || focused) KanvasColors.Accent else KanvasColors.Border),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 74.dp).semantics {
            role = Role.RadioButton
            this.selected = selected
        },
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(palette.background, palette.elevated, palette.secondary, palette.accent).forEach { color ->
                    Box(Modifier.size(15.dp).background(color, CircleShape).border(1.dp, KanvasColors.Muted.copy(alpha = .45f), CircleShape))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(themeName(theme), style = MaterialTheme.typography.titleSmall)
                    if (theme == AppTheme.KELLI) Icon(KanvasBat, null, Modifier.size(20.dp), tint = palette.secondary)
                }
                Text(themeDescription(theme), style = MaterialTheme.typography.bodySmall, color = KanvasColors.Muted)
            }
            if (selected) Icon(Icons.Filled.Check, "Selected theme", Modifier.size(22.dp), tint = KanvasColors.Accent)
        }
    }
}
