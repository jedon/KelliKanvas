package com.jedon.kellikanvas.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.catalog.preferences.HomeControl

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("ktlint:standard:function-naming")
@Composable
fun HomeScreen(
    collectionLabel: String,
    canStartSlideshow: Boolean,
    onStartSlideshow: () -> Unit,
    onOpenCollection: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenAmbient: () -> Unit,
    onUpdateHomeControl: (HomeControl) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current
    BackHandler {
        activity?.finish()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(collectionLabel.ifBlank { "KelliKanvas" }) },
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Collection") },
                            onClick = {
                                expanded = false
                                onOpenCollection()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Appearance") },
                            onClick = {
                                expanded = false
                                onOpenAppearance()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Playback") },
                            onClick = {
                                expanded = false
                                onOpenPlayback()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Ambient and System") },
                            onClick = {
                                expanded = false
                                onOpenAmbient()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!canStartSlideshow) {
                Text(
                    text = "Add a photos folder in Collection to start the slideshow.",
                )
            }
            Button(
                onClick = {
                    onUpdateHomeControl(HomeControl.START_OR_RESUME)
                    onStartSlideshow()
                },
                enabled = canStartSlideshow,
            ) {
                Text("Start or Resume Slideshow")
            }
        }
    }
}
