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

/** Icons per drawer destination; add a line here when a new destination is added. */
private val tvDrawerIcons: Map<TvHomeDestination, ImageVector> = mapOf(
    TvHomeDestination.Home to Icons.Filled.Home,
    TvHomeDestination.Collection to Icons.AutoMirrored.Filled.List,
    TvHomeDestination.Appearance to Icons.Filled.Edit,
    TvHomeDestination.Playback to Icons.Filled.PlayArrow,
    TvHomeDestination.Ambient to Icons.Filled.Settings,
)

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

    fun onDestinationClick(destination: TvHomeDestination) {
        if (destination.inShell) {
            selectedDestination = destination
            return
        }
        when (destination) {
            TvHomeDestination.Appearance -> onOpenAppearance()
            TvHomeDestination.Playback -> onOpenPlayback()
            TvHomeDestination.Ambient -> onOpenAmbient()
            else -> Unit
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
                                    imageVector = tvDrawerIcons.getValue(destination),
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
    LaunchedEffect(canStartSlideshow) {
        if (canStartSlideshow) {
            startFocusRequester.requestFocus()
        }
    }
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
