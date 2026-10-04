package com.jedon.kellikanvas.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.ui.tv.KanvasBrand
import com.jedon.kellikanvas.ui.tv.KanvasButton
import com.jedon.kellikanvas.ui.tv.KanvasColors
import com.jedon.kellikanvas.ui.tv.KanvasPageHeader
import com.jedon.kellikanvas.ui.tv.isTelevisionUi

private fun settingsDescription(title: String): String = when (title) {
    "Appearance" -> "Make your gallery your own. Choose a theme, then adjust photo layouts and display details."
    "Playback" -> "Set the pace of your gallery. Choose timing and how your slideshow moves between moments."
    "Ambient" -> "A display that follows your day. Configure brightness, presence and when your gallery is active."
    "System" -> "Manage your screensaver, application updates and device information."
    "Diagnostics" -> "Check your photo sources and connections when something needs attention."
    else -> "Customize your KelliKanvas experience."
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun SettingsScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    SettingsMaterialTheme {
        BackHandler(onBack = onBack)
        val inputMode = LocalInputModeManager.current
        val television = LocalContext.current.isTelevisionUi()
        LaunchedEffect(television) {
            if (television) inputMode.requestInputMode(InputMode.Keyboard)
        }
        BoxWithConstraints(modifier.fillMaxSize().background(KanvasColors.Background).safeDrawingPadding()) {
            if (maxWidth >= 700.dp) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(260.dp).fillMaxSize().background(KanvasColors.Rail).padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        KanvasButton("Gallery", onBack, icon = Icons.AutoMirrored.Filled.ArrowBack)
                        KanvasBrand()
                        KanvasPageHeader(title, settingsDescription(title), eyebrow = "SETTINGS")
                        HorizontalDivider(color = KanvasColors.Border)
                        Text(
                            "Use ↑ ↓ to browse.\nPress OK to change a setting.\nPress Back to return.",
                            color = KanvasColors.Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = content,
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        KanvasPageHeader(title, "Make your gallery your own.", Modifier.weight(1f), "SETTINGS")
                        KanvasButton("Back", onBack)
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content,
                    )
                }
            }
        }
    }
}
