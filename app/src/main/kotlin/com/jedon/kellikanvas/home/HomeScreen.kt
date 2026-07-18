package com.jedon.kellikanvas.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jedon.kellikanvas.catalog.preferences.HomeControl

@Suppress("ktlint:standard:function-naming")
@Composable
fun HomeScreen(
    collectionLabel: String,
    canStartSlideshow: Boolean,
    initialFocus: HomeControl,
    onStartSlideshow: () -> Unit,
    onUpdateHomeControl: (HomeControl) -> Unit,
    modifier: Modifier = Modifier,
) {
    var message by remember { mutableStateOf<String?>(null) }
    val controls = remember {
        listOf(
            HomeControl.START_OR_RESUME to "Start or Resume Slideshow",
            HomeControl.COLLECTION to "Collection",
            HomeControl.APPEARANCE to "Appearance",
            HomeControl.PLAYBACK to "Playback",
            HomeControl.AMBIENT_AND_SYSTEM to "Ambient and System",
        )
    }
    val requesters = remember { controls.associate { it.first to FocusRequester() } }

    LaunchedEffect(initialFocus) {
        requesters[initialFocus]?.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp)
            .widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = collectionLabel,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Home",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        controls.forEach { (control, label) ->
            val enabled = control != HomeControl.START_OR_RESUME || canStartSlideshow
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(requesters.getValue(control))
                    .onFocusChanged { state ->
                        if (state.isFocused) onUpdateHomeControl(control)
                    }
                    .focusable(enabled = enabled)
                    .clickable(enabled = enabled) {
                        onUpdateHomeControl(control)
                        if (control == HomeControl.START_OR_RESUME) {
                            onStartSlideshow()
                        } else {
                            message = "$label — Coming next"
                        }
                    }
                    .background(
                        if (enabled) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
                        },
                    )
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    },
                )
                if (control != HomeControl.START_OR_RESUME && message?.startsWith(label) == true) {
                    Text(
                        text = "Coming next",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        message?.let {
            Text(
                text = it,
                color = Color.LightGray,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
