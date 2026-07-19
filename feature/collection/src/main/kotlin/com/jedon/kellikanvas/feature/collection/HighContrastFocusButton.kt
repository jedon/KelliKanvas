package com.jedon.kellikanvas.feature.collection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val FocusBorder = Color(0xFFFFEB3B)
private val FocusContainer = Color(0xFF0D47A1)
private val IdleContainer = Color(0xFF1565C0)

/**
 * TV-friendly button: bright yellow border + darker blue fill when focused.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun HighContrastFocusButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeightDp: Int = 64,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        border = if (focused) BorderStroke(4.dp, FocusBorder) else BorderStroke(2.dp, Color.Transparent),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) FocusContainer else IdleContainer,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF90A4AE),
            disabledContentColor = Color.White,
        ),
        modifier = modifier.heightIn(min = minHeightDp.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}
