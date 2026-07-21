package com.jedon.kellikanvas.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border

/**
 * Single source of truth for the app's "unmistakable from the couch" focus
 * treatment: bright yellow border, darker fill while focused.
 */
object HighContrastFocusDefaults {
    val BorderColor = Color(0xFFFFEB3B)
    val FocusedContainerColor = Color(0xFF0D47A1)
    val IdleContainerColor = Color(0xFF1565C0)
    val BorderWidth = 4.dp

    /** For tv-material `border` params ([androidx.tv.material3.Border] follows the button's own shape). */
    val TvFocusedBorder = Border(BorderStroke(BorderWidth, BorderColor))
}

/**
 * Draws the high-contrast focus border around any focusable element
 * (Material3 buttons, toggleable rows, ...) when it holds D-pad focus.
 */
@Composable
fun Modifier.highContrastFocus(shape: Shape = RoundedCornerShape(percent = 50)): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused) {
                Modifier.border(
                    width = HighContrastFocusDefaults.BorderWidth,
                    color = HighContrastFocusDefaults.BorderColor,
                    shape = shape,
                )
            } else {
                Modifier
            },
        )
}
