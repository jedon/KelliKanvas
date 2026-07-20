package com.jedon.kellikanvas.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.feature.collection.CollectionHubScreen
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.ui.PhoneMaterialTheme
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText

/** Exhaustive so the compiler flags a missing icon when a destination is added. */
private val TvHomeDestination.icon: ImageVector
    get() = when (this) {
        TvHomeDestination.Home -> Icons.Filled.Home
        TvHomeDestination.Collection -> Icons.AutoMirrored.Filled.List
        TvHomeDestination.Appearance -> Icons.Filled.Edit
        TvHomeDestination.Playback -> Icons.Filled.PlayArrow
        TvHomeDestination.Ambient -> Icons.Filled.Settings
    }

/**
 * TV home shell: a tv-material [NavigationDrawer] on the left (collapsed to icons,
 * expands on focus) with the selected in-shell destination rendered as main content.
 *
 * No key-event interception and no container focusables: DPAD Left from content
 * reaches the drawer through normal Compose focus search.
 */
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
    onAddLocalFolder: () -> Unit,
    onAddQnap: () -> Unit,
    onConnectHouseholdNas: () -> Unit,
    onRemoveRoot: (SelectedRoot) -> Unit,
    modifier: Modifier = Modifier,
    bootstrapUi: PhotosBootstrapUi = PhotosBootstrapUi.Idle,
    bootstrapError: String? = null,
    onRetryBootstrap: () -> Unit = {},
    collectionLoadError: String? = null,
) {
    val activity = LocalActivity.current
    var selectedDestination by rememberSaveable { mutableStateOf(TvHomeDestination.Home) }
    val startFocusRequester = remember { FocusRequester() }
    var initialCtaFocusRequested by rememberSaveable { mutableStateOf(false) }

    // One-shot initial focus on the Start CTA; must not refire when returning from
    // Collection or when canStartSlideshow flips later in the session.
    LaunchedEffect(canStartSlideshow, selectedDestination) {
        if (
            !initialCtaFocusRequested &&
            canStartSlideshow &&
            selectedDestination == TvHomeDestination.Home
        ) {
            initialCtaFocusRequested = true
            startFocusRequester.requestFocus()
        }
    }

    fun onDestinationClick(destination: TvHomeDestination) {
        if (destination.inShell) {
            selectedDestination = destination
            return
        }
        // Exhaustive so the compiler flags a forgotten callback for future entries.
        when (destination) {
            TvHomeDestination.Appearance -> onOpenAppearance()
            TvHomeDestination.Playback -> onOpenPlayback()
            TvHomeDestination.Ambient -> onOpenAmbient()
            TvHomeDestination.Home, TvHomeDestination.Collection -> Unit
        }
    }

    // Collection hosts its own BackHandler (returns to Home); the shell handles the rest.
    BackHandler(enabled = selectedDestination != TvHomeDestination.Collection) {
        val backTarget = tvHomeBackTarget(selectedDestination)
        if (backTarget != null) {
            selectedDestination = backTarget
        } else {
            activity?.finish()
        }
    }

    TvMaterialTheme {
        NavigationDrawer(
            modifier = modifier.fillMaxSize(),
            drawerContent = {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(TvMaterialTheme.colorScheme.surface)
                        .padding(12.dp)
                        .selectableGroup()
                        .focusRestorer(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tvHomeDrawerDestinations.forEach { destination ->
                        NavigationDrawerItem(
                            selected = destination.inShell && destination == selectedDestination,
                            onClick = { onDestinationClick(destination) },
                            leadingContent = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null,
                                )
                            },
                        ) {
                            TvText(destination.label)
                        }
                    }
                }
            },
        ) {
            // The content pane hosts shared Material3 screens (CollectionHubScreen,
            // HomeCenterPage) that resolve the phone color scheme, so re-wrap it here.
            PhoneMaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    when (selectedDestination) {
                        TvHomeDestination.Collection -> CollectionHubScreen(
                            roots = roots,
                            sourceLabels = sourceLabels,
                            onAddLocalFolder = onAddLocalFolder,
                            onAddQnap = onAddQnap,
                            onConnectHouseholdNas = onConnectHouseholdNas,
                            onRemoveRoot = onRemoveRoot,
                            onBack = { selectedDestination = TvHomeDestination.Home },
                            loadError = collectionLoadError,
                        )
                        else -> TvHomeContent(
                            collectionLabel = collectionLabel,
                            canStartSlideshow = canStartSlideshow,
                            bootstrapUi = bootstrapUi,
                            bootstrapError = bootstrapError,
                            onRetryBootstrap = onRetryBootstrap,
                            onStartSlideshow = onStartSlideshow,
                            startFocusRequester = startFocusRequester,
                        )
                    }
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun TvHomeContent(
    collectionLabel: String,
    canStartSlideshow: Boolean,
    bootstrapUi: PhotosBootstrapUi,
    bootstrapError: String?,
    onRetryBootstrap: () -> Unit,
    onStartSlideshow: () -> Unit,
    startFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues()),
    ) {
        Text(
            text = collectionLabel.ifBlank { "KelliKanvas" },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 32.dp, top = 24.dp),
        )
        HomeCenterPage(
            canStartSlideshow = canStartSlideshow,
            bootstrapUi = bootstrapUi,
            bootstrapError = bootstrapError,
            onRetryBootstrap = onRetryBootstrap,
            onStartSlideshow = onStartSlideshow,
            startFocusRequester = startFocusRequester,
            noPhotosHint = "Add a photos folder in Collection (press ← to open the menu).",
            primaryHint = "OK starts slideshow · ← opens the menu",
            secondaryHint = "Back exits slideshow to Home",
        )
    }
}
