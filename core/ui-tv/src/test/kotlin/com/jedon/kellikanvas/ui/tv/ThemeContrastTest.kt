package com.jedon.kellikanvas.ui.tv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertWithMessage
import com.jedon.kellikanvas.model.AppTheme
import org.junit.Test

class ThemeContrastTest {
    @Test fun presetTextAndButtonLabelsMeetNormalTextContrast() {
        AppTheme.entries.filterNot { it == AppTheme.SYSTEM }.forEach { theme ->
            val palette = kanvasPalette(theme)
            listOf(palette.background, palette.rail, palette.surface, palette.elevated).forEach { surface ->
                assertWithMessage("$theme main text").that(contrast(palette.text, surface)).isAtLeast(4.5)
                assertWithMessage("$theme supporting text").that(contrast(palette.muted, surface)).isAtLeast(4.5)
            }
            assertWithMessage("$theme button labels").that(contrast(palette.onAccent, palette.accent)).isAtLeast(4.5)
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }
}
