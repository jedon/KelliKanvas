package com.jedon.kellikanvas.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Phone Material3 chrome must not inherit TV MaterialTheme locals from [com.jedon.kellikanvas.MainActivity].
 * Force a high-contrast light scheme so text and icons stay readable.
 */
private val PhoneColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    surface = Color.White,
    onSurface = Color.Black,
    onSurfaceVariant = Color(0xFF424242),
    background = Color.White,
    onBackground = Color.Black,
    error = Color(0xFFB00020),
)

@Suppress("ktlint:standard:function-naming")
@Composable
fun PhoneMaterialTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PhoneColorScheme, content = content)
}
