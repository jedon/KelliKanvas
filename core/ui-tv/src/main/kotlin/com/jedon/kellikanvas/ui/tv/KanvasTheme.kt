package com.jedon.kellikanvas.ui.tv

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

/** Shared chrome for every form factor; artwork retains its own rendering colors. */
object KanvasColors {
    val Background = Color(0xFF101514)
    val Rail = Color(0xFF151B19)
    val Surface = Color(0xFF1B2320)
    val Elevated = Color(0xFF25302B)
    val Border = Color(0xFF34423B)
    val Text = Color(0xFFF1F3ED)
    val Muted = Color(0xFFA8B5AB)
    val Accent = Color(0xFFBBD8B0)
    val OnAccent = Color(0xFF18251A)
    val Error = Color(0xFFFFB4A8)
}

private val kanvasScheme = darkColorScheme(
    primary = KanvasColors.Accent,
    onPrimary = KanvasColors.OnAccent,
    primaryContainer = KanvasColors.Elevated,
    onPrimaryContainer = KanvasColors.Text,
    secondary = KanvasColors.Accent,
    onSecondary = KanvasColors.OnAccent,
    background = KanvasColors.Background,
    onBackground = KanvasColors.Text,
    surface = KanvasColors.Surface,
    onSurface = KanvasColors.Text,
    surfaceVariant = KanvasColors.Elevated,
    onSurfaceVariant = KanvasColors.Muted,
    surfaceContainer = KanvasColors.Surface,
    surfaceContainerHigh = KanvasColors.Elevated,
    outline = KanvasColors.Border,
    error = KanvasColors.Error,
)

@Suppress("ktlint:standard:function-naming")
@Composable
fun KanvasTheme(content: @Composable () -> Unit) {
    TvMaterialTheme(
        colorScheme = tvDarkColorScheme(
            primary = KanvasColors.Accent,
            onPrimary = KanvasColors.OnAccent,
            background = KanvasColors.Background,
            onBackground = KanvasColors.Text,
            surface = KanvasColors.Surface,
            onSurface = KanvasColors.Text,
            surfaceVariant = KanvasColors.Elevated,
            onSurfaceVariant = KanvasColors.Muted,
            error = KanvasColors.Error,
        ),
    ) {
        MaterialTheme(
            colorScheme = kanvasScheme,
            shapes = Shapes(
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(16.dp),
            ),
        ) {
            CompositionLocalProvider(LocalContentColor provides KanvasColors.Text, content = content)
        }
    }
}
