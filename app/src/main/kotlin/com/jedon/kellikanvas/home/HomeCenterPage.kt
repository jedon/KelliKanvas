package com.jedon.kellikanvas.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.feature.collection.HighContrastFocusButton

/**
 * Bootstrap status plus the Start-slideshow CTA, shared by the phone pager Home page
 * and the TV drawer shell. Hint texts differ per form factor, so callers supply them.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun HomeCenterPage(
    canStartSlideshow: Boolean,
    bootstrapUi: PhotosBootstrapUi,
    bootstrapError: String?,
    onRetryBootstrap: () -> Unit,
    onStartSlideshow: () -> Unit,
    startFocusRequester: FocusRequester,
    noPhotosHint: String,
    primaryHint: String,
    secondaryHint: String?,
    modifier: Modifier = Modifier,
    updateAvailableVersion: String? = null,
    onOpenSystem: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (updateAvailableVersion != null) {
            HighContrastFocusButton(
                onClick = onOpenSystem,
                label = "Update available (v$updateAvailableVersion) — go to System",
                minHeightDp = 56,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when (bootstrapUi) {
            PhotosBootstrapUi.Connecting -> {
                CircularProgressIndicator()
                Text(
                    text = "Connecting to photos…",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            PhotosBootstrapUi.Failed -> {
                Text(
                    text = bootstrapError ?: "Could not connect to household photos.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                HighContrastFocusButton(
                    onClick = onRetryBootstrap,
                    label = "Retry",
                    minHeightDp = 56,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PhotosBootstrapUi.Idle -> {
                if (!canStartSlideshow) {
                    Text(
                        text = noPhotosHint,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        HighContrastFocusButton(
            onClick = onStartSlideshow,
            label = "Start or Resume Slideshow",
            enabled = canStartSlideshow,
            minHeightDp = 56,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(startFocusRequester),
        )
        Text(
            text = primaryHint,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (secondaryHint != null) {
            Text(
                text = secondaryHint,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
