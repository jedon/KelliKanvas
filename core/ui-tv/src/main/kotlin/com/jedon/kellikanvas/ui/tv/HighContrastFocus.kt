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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border

/**
 * Single source of truth for the app's "unmistakable from the couch" focus
 * treatment: a contrasting outline and the selected theme's accent while focused.
 */
object HighContrastFocusDefaults {
    val BorderColor @Composable get() = KanvasColors.Text
    val FocusedContainerColor @Composable get() = KanvasColors.Accent
    val IdleContainerColor @Composable get() = KanvasColors.Elevated
    val BorderWidth = 2.dp

    /** For tv-material `border` params ([androidx.tv.material3.Border] follows the button's own shape). */
    val TvFocusedBorder @Composable get() = Border(BorderStroke(BorderWidth, BorderColor))
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
