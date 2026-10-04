package com.jedon.kellikanvas.ui.tv

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.model.AppTheme
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme
import androidx.tv.material3.lightColorScheme as tvLightColorScheme

@Immutable
data class KanvasPalette(
    val background: Color,
    val rail: Color,
    val surface: Color,
    val elevated: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val secondary: Color,
    val error: Color = Color(0xFFFFB4A8),
    val dark: Boolean = true,
)

/** Presets are shared by theme previews and both Material libraries. */
fun kanvasPalette(theme: AppTheme): KanvasPalette = when (theme) {
    AppTheme.KELLI -> KanvasPalette(
        Color(0xFF0D0912), Color(0xFF140D1E), Color(0xFF20152D), Color(0xFF342247),
        Color(0xFF604577), Color(0xFFF8F0FC), Color(0xFFC8B6D3), Color(0xFFE8C76B),
        Color(0xFF261C08), Color(0xFFCBA5EE),
    )
    AppTheme.GALLERY -> KanvasPalette(
        Color(0xFF101514), Color(0xFF151B19), Color(0xFF1B2320), Color(0xFF25302B),
        Color(0xFF34423B), Color(0xFFF1F3ED), Color(0xFFA8B5AB), Color(0xFFBBD8B0),
        Color(0xFF18251A), Color(0xFFBBD8B0),
    )
    AppTheme.MIDNIGHT, AppTheme.SYSTEM -> KanvasPalette(
        Color(0xFF090D14), Color(0xFF101722), Color(0xFF172131), Color(0xFF23334B),
        Color(0xFF415776), Color(0xFFF0F4FC), Color(0xFFADBCD2), Color(0xFFAACBFA),
        Color(0xFF10243F), Color(0xFFBAC3EC),
    )
    AppTheme.PAPER -> KanvasPalette(
        Color(0xFFF6F3EC), Color(0xFFECE7DC), Color(0xFFFFFCF5), Color(0xFFE8E0CF),
        Color(0xFFBDB19D), Color(0xFF27231B), Color(0xFF62594C), Color(0xFF705225),
        Color(0xFFFFFFFF), Color(0xFF745784), Color(0xFFA52C2C), dark = false,
    )
}

val LocalKanvasTheme = staticCompositionLocalOf { AppTheme.KELLI }
private val LocalKanvasPalette = staticCompositionLocalOf { kanvasPalette(AppTheme.KELLI) }

/** Composition-scoped colors avoid global mutation and update every screen immediately. */
object KanvasColors {
    val Background: Color @Composable get() = LocalKanvasPalette.current.background
    val Rail: Color @Composable get() = LocalKanvasPalette.current.rail
    val Surface: Color @Composable get() = LocalKanvasPalette.current.surface
    val Elevated: Color @Composable get() = LocalKanvasPalette.current.elevated
    val Border: Color @Composable get() = LocalKanvasPalette.current.border
    val Text: Color @Composable get() = LocalKanvasPalette.current.text
    val Muted: Color @Composable get() = LocalKanvasPalette.current.muted
    val Accent: Color @Composable get() = LocalKanvasPalette.current.accent
    val OnAccent: Color @Composable get() = LocalKanvasPalette.current.onAccent
    val Secondary: Color @Composable get() = LocalKanvasPalette.current.secondary
    val Error: Color @Composable get() = LocalKanvasPalette.current.error
}

@Composable
fun kanvasResolvedPalette(theme: AppTheme): KanvasPalette {
    if (theme != AppTheme.SYSTEM) return kanvasPalette(theme)
    val context = LocalContext.current
    val dark = context.isTelevisionUi() || isSystemInDarkTheme()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return kanvasPalette(if (dark) AppTheme.MIDNIGHT else AppTheme.PAPER)
    val colors = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return KanvasPalette(
        colors.background, colors.surfaceContainerLow, colors.surfaceContainer, colors.surfaceContainerHigh,
        colors.outlineVariant, colors.onSurface, colors.onSurfaceVariant, colors.primary, colors.onPrimary,
        colors.secondary, colors.error, dark,
    )
}

private fun KanvasPalette.materialColors(): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent, primaryContainer = elevated, onPrimaryContainer = text,
        secondary = secondary, onSecondary = background, secondaryContainer = elevated, onSecondaryContainer = text,
        tertiary = secondary, onTertiary = background,
        background = background, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = elevated, onSurfaceVariant = muted, surfaceContainer = surface,
        surfaceContainerLowest = background, surfaceContainerLow = rail, surfaceContainerHigh = elevated,
        surfaceContainerHighest = elevated, surfaceBright = elevated, surfaceDim = background,
        outline = border, outlineVariant = border, error = error,
    )
}

/** Nested wrappers inherit the saved selection instead of resetting to a default. */
@Suppress("ktlint:standard:function-naming")
@Composable
fun KanvasTheme(theme: AppTheme = LocalKanvasTheme.current, content: @Composable () -> Unit) {
    val palette = kanvasResolvedPalette(theme)
    val tvBase = if (palette.dark) tvDarkColorScheme() else tvLightColorScheme()
    CompositionLocalProvider(LocalKanvasTheme provides theme, LocalKanvasPalette provides palette) {
        TvMaterialTheme(
            colorScheme = tvBase.copy(
                primary = palette.accent, onPrimary = palette.onAccent, secondary = palette.secondary,
                background = palette.background, onBackground = palette.text, surface = palette.surface, onSurface = palette.text,
                surfaceVariant = palette.elevated, onSurfaceVariant = palette.muted, error = palette.error,
            ),
        ) {
            MaterialTheme(
                colorScheme = palette.materialColors(),
                shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp)),
            ) {
                CompositionLocalProvider(LocalContentColor provides palette.text, content = content)
            }
        }
    }
}
