package com.jedon.kellikanvas.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.preferences.HomeControl
import com.jedon.kellikanvas.feature.collection.CollectionHubScreen
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.SourceAdapter
import com.jedon.kellikanvas.ui.tv.KanvasColors
import com.jedon.kellikanvas.ui.tv.KanvasNavigationItem
import com.jedon.kellikanvas.ui.tv.KanvasPageHeader
import com.jedon.kellikanvas.ui.tv.KanvasTheme
import com.jedon.kellikanvas.ui.tv.isTelevisionUi

enum class PhotosBootstrapUi { Idle, Connecting, Failed }

@Suppress("ktlint:standard:function-naming")
@Composable
fun HomeScreen(
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
    onUpdateHomeControl: (HomeControl) -> Unit,
    modifier: Modifier = Modifier,
    bootstrapUi: PhotosBootstrapUi = PhotosBootstrapUi.Idle,
    bootstrapError: String? = null,
    onRetryBootstrap: () -> Unit = {},
    collectionLoadError: String? = null,
    autoStartSlideshowToken: Int = 0,
    onAutoStartSlideshowConsumed: () -> Unit = {},
    updateAvailableVersion: String? = null,
    sourceNotices: List<String> = emptyList(),
    adapters: Map<SourceProfileId, SourceAdapter> = emptyMap(),
    tailscalePrompt: Boolean = false,
    onOpenTailscale: () -> Unit = {},
    onAddGoogleDrive: () -> Unit = {},
    onAddGooglePhotos: () -> Unit = {},
    onReconnectGoogle: (SourceProfileId) -> Unit = {},
    onBrowseConnectors: () -> Unit = {},
) {
    LaunchedEffect(autoStartSlideshowToken, canStartSlideshow) {
        if (AutoStartSlideshowToken.consumeIfReady(autoStartSlideshowToken, canStartSlideshow, onAutoStartSlideshowConsumed)) {
            onUpdateHomeControl(HomeControl.START_OR_RESUME)
            onStartSlideshow()
        }
    }
    val preview = rememberGalleryPreview(adapters, roots)
    val start: () -> Unit = {
        onUpdateHomeControl(HomeControl.START_OR_RESUME)
        onStartSlideshow()
    }
    if (LocalContext.current.isTelevisionUi()) {
        TvHomeShell(
            collectionLabel, canStartSlideshow, roots, sourceLabels, start,
            onOpenAppearance, onOpenPlayback, onOpenAmbient, onOpenSystem, onOpenDiagnostics,
            onAddLocalFolder, onAddQnap, onConnectHouseholdNas, onRemoveRoot, modifier,
            bootstrapUi, bootstrapError, onRetryBootstrap, collectionLoadError,
            updateAvailableVersion, sourceNotices, preview.image, preview.loading,
            tailscalePrompt, onOpenTailscale,
            onAddGoogleDrive, onAddGooglePhotos, onReconnectGoogle, onBrowseConnectors,
        )
        return
    }
    val activity = LocalActivity.current
    var tab by rememberSaveable { mutableStateOf(TvHomeDestination.Home) }
    val startFocus = remember { FocusRequester() }
    BackHandler(enabled = tab != TvHomeDestination.Collection) {
        if (tab != TvHomeDestination.Home) tab = TvHomeDestination.Home else activity?.finish()
    }
    KanvasTheme {
        Column(modifier.fillMaxSize().background(KanvasColors.Background).safeDrawingPadding()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    TvHomeDestination.Home -> HomeCenterPage(
                        canStartSlideshow, bootstrapUi, bootstrapError, onRetryBootstrap, start, startFocus,
                        "Connect a photo folder to bring your favorite moments to the big screen.",
                        "Your photos. Your space. Your gallery.", null,
                        updateAvailableVersion = updateAvailableVersion, onOpenSystem = onOpenSystem,
                        sourceNotices = sourceNotices, collectionLabel = collectionLabel, folderCount = roots.size,
                        preview = preview.image, previewLoading = preview.loading,
                        onOpenCollection = { tab = TvHomeDestination.Collection },
                        onOpenAppearance = onOpenAppearance, onOpenAmbient = onOpenAmbient,
                        tailscalePrompt = tailscalePrompt, onOpenTailscale = onOpenTailscale,
                    )
                    TvHomeDestination.Collection -> CollectionHubScreen(
                        roots,
                        sourceLabels,
                        onAddLocalFolder,
                        onAddQnap,
                        onConnectHouseholdNas,
                        onRemoveRoot,
                        { tab = TvHomeDestination.Home },
                        loadError = collectionLoadError,
                        onAddGoogleDrive = onAddGoogleDrive,
                        onAddGooglePhotos = onAddGooglePhotos,
                        onReconnectGoogle = onReconnectGoogle,
                        onBrowseConnectors = onBrowseConnectors,
                    )
                    else -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        KanvasPageHeader("Settings", "Create a display that feels at home.")
                        KanvasNavigationItem("Appearance", Icons.Filled.Edit, false, onOpenAppearance)
                        KanvasNavigationItem("Playback", Icons.Filled.PlayArrow, false, onOpenPlayback)
                        KanvasNavigationItem("Ambient", Icons.Filled.Settings, false, onOpenAmbient)
                        KanvasNavigationItem("System", Icons.Filled.Info, false, onOpenSystem)
                        KanvasNavigationItem("Diagnostics", Icons.Filled.Build, false, onOpenDiagnostics)
                    }
                }
            }
            HorizontalDivider(color = KanvasColors.Border)
            Row(Modifier.background(KanvasColors.Rail).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KanvasNavigationItem(
                    "Home",
                    Icons.Filled.Home,
                    tab == TvHomeDestination.Home,
                    { tab = TvHomeDestination.Home },
                    Modifier.weight(1f),
                )
                KanvasNavigationItem(
                    "Photos",
                    Icons.AutoMirrored.Filled.List,
                    tab == TvHomeDestination.Collection,
                    { tab = TvHomeDestination.Collection },
                    Modifier.weight(1f),
                )
                KanvasNavigationItem(
                    "Settings",
                    Icons.Filled.Settings,
                    tab == TvHomeDestination.System,
                    { tab = TvHomeDestination.System },
                    Modifier.weight(1f),
                )
            }
        }
    }
}
