package com.jedon.kellikanvas.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.feature.collection.CollectionHubScreen
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.ui.tv.KanvasColors
import com.jedon.kellikanvas.ui.tv.KanvasNavigationItem
import com.jedon.kellikanvas.ui.tv.KanvasTheme

private val TvHomeDestination.icon: ImageVector
    get() = when (this) {
        TvHomeDestination.Home -> Icons.Filled.Home
        TvHomeDestination.Collection -> Icons.AutoMirrored.Filled.List
        TvHomeDestination.Appearance -> Icons.Filled.Edit
        TvHomeDestination.Playback -> Icons.Filled.PlayArrow
        TvHomeDestination.Ambient -> Icons.Filled.Settings
        TvHomeDestination.System -> Icons.Filled.Info
        TvHomeDestination.Diagnostics -> Icons.Filled.Build
    }

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun TvHomeShell(
    collectionLabel: String,
    canStartSlideshow: Boolean,
    roots: List<SelectedRoot>,
    sourceLabels: Map<SourceProfileId, String>,
    onStartSlideshow: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenAmbient: () -> Unit,
    onOpenSystem: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onAddLocalFolder: () -> Unit,
    onAddQnap: () -> Unit,
    onConnectHouseholdNas: () -> Unit,
    onRemoveRoot: (SelectedRoot) -> Unit,
    modifier: Modifier = Modifier,
    bootstrapUi: PhotosBootstrapUi = PhotosBootstrapUi.Idle,
    bootstrapError: String? = null,
    onRetryBootstrap: () -> Unit = {},
    collectionLoadError: String? = null,
    updateAvailableVersion: String? = null,
    sourceNotices: List<String> = emptyList(),
    preview: ImageBitmap? = null,
    previewLoading: Boolean = false,
    tailscalePrompt: Boolean = false,
    onOpenTailscale: () -> Unit = {},
) {
    val activity = LocalActivity.current
    var selectedDestination by rememberSaveable { mutableStateOf(TvHomeDestination.Home) }
    val startFocusRequester = remember { FocusRequester() }
    val collectionFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by rememberSaveable { mutableStateOf(false) }
    val inputMode = LocalInputModeManager.current

    LaunchedEffect(canStartSlideshow, bootstrapUi, selectedDestination) {
        if (!initialFocusRequested && selectedDestination == TvHomeDestination.Home) {
            inputMode.requestInputMode(InputMode.Keyboard)
            withFrameNanos { }
            initialFocusRequested = if (canStartSlideshow) {
                startFocusRequester.requestFocus()
            } else {
                collectionFocusRequester.requestFocus()
            }
        }
    }

    fun onDestinationClick(destination: TvHomeDestination) {
        if (destination.inShell) {
            selectedDestination = destination
            return
        }
        when (destination) {
            TvHomeDestination.Appearance -> onOpenAppearance()
            TvHomeDestination.Playback -> onOpenPlayback()
            TvHomeDestination.Ambient -> onOpenAmbient()
            TvHomeDestination.System -> onOpenSystem()
            TvHomeDestination.Diagnostics -> onOpenDiagnostics()
            TvHomeDestination.Home, TvHomeDestination.Collection -> Unit
        }
    }

    BackHandler(enabled = selectedDestination != TvHomeDestination.Collection) {
        val backTarget = tvHomeBackTarget(selectedDestination)
        if (backTarget != null) selectedDestination = backTarget else activity?.finish()
    }

    KanvasTheme {
        Row(modifier.fillMaxSize().background(KanvasColors.Background).safeDrawingPadding()) {
            Column(
                Modifier.width(184.dp).fillMaxHeight().background(KanvasColors.Rail)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp)
                    .focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("KelliKanvas", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp))
                Text(
                    "YOUR PERSONAL GALLERY",
                    color = KanvasColors.Muted,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 24.dp),
                )
                tvHomeDrawerDestinations.forEach { destination ->
                    if (destination == TvHomeDestination.Appearance || destination == TvHomeDestination.System) {
                        HorizontalDivider(color = KanvasColors.Border, modifier = Modifier.padding(vertical = 10.dp))
                    }
                    KanvasNavigationItem(
                        destination.label,
                        destination.icon,
                        selected = destination == selectedDestination,
                        onClick = { onDestinationClick(destination) },
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "MADE FOR YOUR TV",
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    color = KanvasColors.Muted,
                    modifier = Modifier.padding(start = 12.dp, top = 24.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (selectedDestination) {
                    TvHomeDestination.Collection -> CollectionHubScreen(
                        roots,
                        sourceLabels,
                        onAddLocalFolder,
                        onAddQnap,
                        onConnectHouseholdNas,
                        onRemoveRoot,
                        onBack = { selectedDestination = TvHomeDestination.Home },
                        loadError = collectionLoadError,
                    )
                    else -> HomeCenterPage(
                        canStartSlideshow, bootstrapUi, bootstrapError, onRetryBootstrap,
                        onStartSlideshow, startFocusRequester,
                        noPhotosHint = "Connect a photo folder to bring your favorite moments to the big screen.",
                        primaryHint = "Navigate with arrows    ·    OK to select    ·    Back to return",
                        secondaryHint = null,
                        collectionLabel = collectionLabel,
                        folderCount = roots.size,
                        preview = preview,
                        previewLoading = previewLoading,
                        onOpenCollection = { selectedDestination = TvHomeDestination.Collection },
                        onOpenAppearance = onOpenAppearance,
                        onOpenAmbient = onOpenAmbient,
                        collectionFocusRequester = collectionFocusRequester,
                        updateAvailableVersion = updateAvailableVersion,
                        onOpenSystem = onOpenSystem,
                        sourceNotices = sourceNotices,
                        tailscalePrompt = tailscalePrompt,
                        onOpenTailscale = onOpenTailscale,
                    )
                }
            }
        }
    }
}
