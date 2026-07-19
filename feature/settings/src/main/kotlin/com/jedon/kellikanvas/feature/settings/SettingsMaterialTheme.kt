package com.jedon.kellikanvas.feature.settings

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.darkColorScheme
import com.jedon.kellikanvas.ui.tv.isTelevisionUi
import androidx.compose.material3.MaterialTheme as PhoneMaterialTheme
import androidx.tv.material3.MaterialTheme as TvMaterialTheme

/**
 * Phone: Material3 light chrome.
 * TV: Compose for TV Material3 dark scheme (focus/glow friendly).
 */
private val PhoneSettingsColorScheme = lightColorScheme(
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
fun SettingsMaterialTheme(content: @Composable () -> Unit) {
    val television = LocalContext.current.isTelevisionUi()
    if (television) {
        TvMaterialTheme(colorScheme = darkColorScheme(), content = content)
    } else {
        PhoneMaterialTheme(colorScheme = PhoneSettingsColorScheme, content = content)
    }
}
