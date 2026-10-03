package com.jedon.kellikanvas.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jedon.kellikanvas.nas.TailscaleHosts
import com.jedon.kellikanvas.ui.tv.KanvasButton
import com.jedon.kellikanvas.ui.tv.KanvasColors
import com.jedon.kellikanvas.ui.tv.KanvasNotice
import com.jedon.kellikanvas.ui.tv.KanvasStatus
import com.jedon.kellikanvas.ui.tv.highContrastFocus

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
    sourceNotices: List<String> = emptyList(),
    collectionLabel: String = "Your photos",
    folderCount: Int = 0,
    preview: ImageBitmap? = null,
    previewLoading: Boolean = false,
    onOpenCollection: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenAmbient: () -> Unit = {},
    collectionFocusRequester: FocusRequester? = null,
    tailscalePrompt: Boolean = false,
    onOpenTailscale: () -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 32.dp else 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("GALLERY", color = KanvasColors.Accent, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                KanvasStatus(
                    label = when {
                        bootstrapUi == PhotosBootstrapUi.Connecting -> "Connecting"
                        sourceNotices.isNotEmpty() ||
                            bootstrapUi == PhotosBootstrapUi.Failed ||
                            tailscalePrompt -> "Needs attention"
                        canStartSlideshow -> "Collection configured"
                        else -> "Let's get started"
                    },
                    active = canStartSlideshow && sourceNotices.isEmpty() && !tailscalePrompt,
                )
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    HeroCopy(
                        canStartSlideshow,
                        collectionLabel,
                        noPhotosHint,
                        onStartSlideshow,
                        onOpenCollection,
                        startFocusRequester,
                        collectionFocusRequester,
                        Modifier.weight(1f),
                    )
                    PreviewPanel(preview, previewLoading, canStartSlideshow, Modifier.weight(1.05f))
                }
            } else {
                PreviewPanel(preview, previewLoading, canStartSlideshow)
                HeroCopy(
                    canStartSlideshow,
                    collectionLabel,
                    noPhotosHint,
                    onStartSlideshow,
                    onOpenCollection,
                    startFocusRequester,
                    collectionFocusRequester,
                )
            }
            if (tailscalePrompt && bootstrapUi != PhotosBootstrapUi.Connecting) {
                KanvasNotice(TailscaleHosts.DISCONNECTED_MESSAGE, error = true)
                KanvasButton("Open Tailscale", onOpenTailscale, primary = true)
            }
            when (bootstrapUi) {
                PhotosBootstrapUi.Connecting -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                    Text("Connecting to your photos…", color = KanvasColors.Muted)
                }
                PhotosBootstrapUi.Failed -> {
                    KanvasNotice(bootstrapError ?: "We couldn't connect to your photos. Check the connection or choose another source.", error = true)
                    KanvasButton("Try again", onRetryBootstrap)
                }
                PhotosBootstrapUi.Idle -> Unit
            }
            sourceNotices.forEach { KanvasNotice(it, error = true) }
            if (updateAvailableVersion != null) {
                KanvasButton("Update available · v$updateAvailableVersion", onOpenSystem)
            }
            HorizontalDivider(color = KanvasColors.Border)
            Text("Make it yours", style = MaterialTheme.typography.titleMedium)
            val collectionDetail = if (folderCount == 0) "Add your first photo source" else "$folderCount photo ${if (folderCount == 1) "folder" else "folders"} configured"
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GalleryShortcut("Collection", collectionDetail, Icons.AutoMirrored.Filled.List, onOpenCollection, Modifier.weight(1f))
                    GalleryShortcut("Appearance", "Layouts, pairing & overlays", Icons.Filled.Edit, onOpenAppearance, Modifier.weight(1f))
                    GalleryShortcut("Ambient", "Light, presence & schedule", Icons.Filled.Settings, onOpenAmbient, Modifier.weight(1f))
                }
            } else {
                GalleryShortcut("Collection", collectionDetail, Icons.AutoMirrored.Filled.List, onOpenCollection)
                GalleryShortcut("Appearance", "Layouts, pairing & overlays", Icons.Filled.Edit, onOpenAppearance)
                GalleryShortcut("Ambient", "Light, presence & schedule", Icons.Filled.Settings, onOpenAmbient)
            }
            Text(primaryHint, color = KanvasColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun HeroCopy(
    ready: Boolean,
    collectionLabel: String,
    noPhotosHint: String,
    onPlay: () -> Unit,
    onCollection: () -> Unit,
    playFocus: FocusRequester,
    collectionFocus: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            if (ready) "A home for\nyour moments." else "Your gallery\nstarts here.",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Normal,
            lineHeight = 39.sp,
        )
        Text(
            if (ready) "${collectionLabel.ifBlank { "Your collection" }}. Beautifully displayed, one moment at a time." else noPhotosHint,
            color = KanvasColors.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ready) {
                KanvasButton("Start slideshow", onPlay, Modifier.focusRequester(playFocus), primary = true, icon = Icons.Filled.PlayArrow)
            }
            KanvasButton(
                if (ready) "Manage collection" else "Connect your photos",
                onCollection,
                modifier = if (collectionFocus != null) Modifier.focusRequester(collectionFocus) else Modifier,
                primary = !ready,
                icon = Icons.AutoMirrored.Filled.List,
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PreviewPanel(preview: ImageBitmap?, loading: Boolean, ready: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GalleryArtwork(preview, Modifier.fillMaxWidth())
        Text(
            when {
                preview != null -> "FROM YOUR COLLECTION"
                loading -> "LOADING COLLECTION PREVIEW"
                ready -> "YOUR PHOTOS WILL APPEAR IN THE SLIDESHOW"
                else -> "A LITTLE INSPIRATION FOR YOUR WALL"
            },
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            color = KanvasColors.Muted,
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun GalleryShortcut(title: String, detail: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        color = KanvasColors.Surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, KanvasColors.Border),
        modifier = modifier.fillMaxWidth().highContrastFocus(RoundedCornerShape(12.dp)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = KanvasColors.Accent)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = KanvasColors.Muted)
        }
    }
}
