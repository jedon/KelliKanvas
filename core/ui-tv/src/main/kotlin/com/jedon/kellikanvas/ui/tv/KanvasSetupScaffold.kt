package com.jedon.kellikanvas.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared source-connection chrome. Platform pickers keep their native behavior. */
@Suppress("ktlint:standard:function-naming")
@Composable
fun KanvasSetupScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize().background(KanvasColors.Background).safeDrawingPadding(),
        containerColor = KanvasColors.Background,
        topBar = {
            Row(
                Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    KanvasPageHeader(title, subtitle, eyebrow = "CONNECT YOUR PHOTOS")
                }
                KanvasButton("Back", onBack, icon = Icons.AutoMirrored.Filled.ArrowBack)
            }
        },
        content = content,
    )
}
