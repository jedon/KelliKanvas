package com.jedon.kellikanvas.home

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.WideButton
import androidx.tv.material3.Text as TvText
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.preferences.HomeControl
import com.jedon.kellikanvas.feature.collection.CollectionHubScreen
import com.jedon.kellikanvas.feature.collection.HighContrastFocusButton
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.ui.PhoneMaterialTheme
import com.jedon.kellikanvas.ui.tv.isTelevisionUi
import kotlinx.coroutines.launch

enum class PhotosBootstrapUi {
    Idle,
    Connecting,
    Failed,
}

@OptIn(ExperimentalMaterial3Api::class)
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
) {
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val pagerFocusRequester = remember { FocusRequester() }
    val startFocusRequester = remember { FocusRequester() }
    val pagerState = rememberPagerState(
        initialPage = PAGE_HOME,
        pageCount = { PAGE_COUNT },
    )

    fun scrollTo(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    fun scrollToFromDpad(page: Int) {
        scope.launch {
            pagerState.animateScrollToPage(page)
            // Child focusables on the old page are disposed when beyondViewportPageCount=0;
            // restore pager focus so subsequent Left/Right still reach onPreviewKeyEvent.
            pagerFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        // Prefer the primary CTA; fall back to the pager when Start cannot take focus.
        if (!startFocusRequester.requestFocus()) {
            pagerFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(autoStartSlideshowToken, canStartSlideshow) {
        // Consume before navigating so Back → Home does not re-fire auto-start.
        if (
            AutoStartSlideshowToken.consumeIfReady(
                token = autoStartSlideshowToken,
                canStart = canStartSlideshow,
                onConsumed = onAutoStartSlideshowConsumed,
            )
        ) {
            onUpdateHomeControl(HomeControl.START_OR_RESUME)
            onStartSlideshow()
        }
    }

    BackHandler(enabled = pagerState.currentPage != PAGE_COLLECTION) {
        if (pagerState.currentPage != PAGE_HOME) {
            scrollTo(PAGE_HOME)
        } else {
            activity?.finish()
        }
    }

    PhoneMaterialTheme {
        val menuTint = Color.Black
        HorizontalPager(
            state = pagerState,
            modifier = modifier
                .fillMaxSize()
                .focusRequester(pagerFocusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action != KeyEvent.ACTION_DOWN || native.repeatCount != 0) {
                        return@onPreviewKeyEvent false
                    }
                    val menuTarget = targetPageForMenuKey(
                        currentPage = pagerState.currentPage,
                        keyCode = native.keyCode,
                    )
                    if (menuTarget != null) {
                        scrollToFromDpad(menuTarget)
                        return@onPreviewKeyEvent true
                    }
                    val target = targetPageForDpad(
                        currentPage = pagerState.currentPage,
                        pageCount = PAGE_COUNT,
                        keyCode = native.keyCode,
                    )
                    if (target != null) {
                        scrollToFromDpad(target)
                        true
                    } else {
                        false
                    }
                },
            beyondViewportPageCount = 0,
        ) { page ->
            when (page) {
                PAGE_MENU -> MenuPage(
                    onOpenCollection = { scrollTo(PAGE_COLLECTION) },
                    onOpenAppearance = onOpenAppearance,
                    onOpenPlayback = onOpenPlayback,
                    onOpenAmbient = onOpenAmbient,
                    onBackToHome = { scrollTo(PAGE_HOME) },
                )
                PAGE_HOME -> Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        TopAppBar(
                            title = { Text(collectionLabel.ifBlank { "KelliKanvas" }) },
                            windowInsets = WindowInsets.statusBars,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.White,
                                titleContentColor = menuTint,
                                actionIconContentColor = menuTint,
                            ),
                            actions = {
                                TextButton(onClick = { scrollTo(PAGE_MENU) }) {
                                    Text("Menu", color = menuTint)
                                }
                            },
                        )
                    },
                ) { padding ->
                    HomeCenterPage(
                        canStartSlideshow = canStartSlideshow,
                        bootstrapUi = bootstrapUi,
                        bootstrapError = bootstrapError,
                        onRetryBootstrap = onRetryBootstrap,
                        onStartSlideshow = {
                            onUpdateHomeControl(HomeControl.START_OR_RESUME)
                            onStartSlideshow()
                        },
                        startFocusRequester = startFocusRequester,
                        modifier = Modifier.padding(padding),
                    )
                }
                PAGE_COLLECTION -> CollectionHubScreen(
                    roots = roots,
                    sourceLabels = sourceLabels,
                    onAddLocalFolder = onAddLocalFolder,
                    onAddQnap = onAddQnap,
                    onConnectHouseholdNas = onConnectHouseholdNas,
                    onRemoveRoot = onRemoveRoot,
                    onBack = { scrollTo(PAGE_HOME) },
                    backHandlerEnabled = pagerState.currentPage == PAGE_COLLECTION,
                    loadError = collectionLoadError,
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun HomeCenterPage(
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
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
                        text = "Add a photos folder in Collection, or open Menu (↑ / Menu) to connect.",
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
            text = "OK starts slideshow · ↑ / Menu opens Menu · ← → pages",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "← Menu · Home · Collection → · Back exits slideshow to Home",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("ktlint:standard:function-naming")
@Composable
private fun MenuPage(
    onOpenCollection: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenAmbient: () -> Unit,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val television = LocalContext.current.isTelevisionUi()
    if (television) {
        TvMaterialTheme {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(WindowInsets.safeDrawing.asPaddingValues())
                    .padding(horizontal = 48.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
            ) {
                TvText(
                    text = "Menu",
                    style = TvMaterialTheme.typography.headlineMedium,
                )
                WideButton(
                    onClick = onBackToHome,
                    modifier = Modifier.fillMaxWidth(),
                    title = { TvText("Home") },
                    subtitle = { TvText("Back or OK") },
                )
                WideButton(
                    onClick = onOpenCollection,
                    modifier = Modifier.fillMaxWidth(),
                    title = { TvText("Collection") },
                    subtitle = { TvText("Photo folders") },
                )
                WideButton(
                    onClick = onOpenAppearance,
                    modifier = Modifier.fillMaxWidth(),
                    title = { TvText("Appearance") },
                    subtitle = { TvText("Layouts and overlays") },
                )
                WideButton(
                    onClick = onOpenPlayback,
                    modifier = Modifier.fillMaxWidth(),
                    title = { TvText("Playback") },
                    subtitle = { TvText("Timing and order") },
                )
                WideButton(
                    onClick = onOpenAmbient,
                    modifier = Modifier.fillMaxWidth(),
                    title = { TvText("Ambient and System") },
                    subtitle = { TvText("Brightness, presence, updates") },
                )
            }
        }
        return
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Menu") },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = Color.Black,
                ),
                actions = {
                    TextButton(onClick = onBackToHome) {
                        Text("Home", color = Color.Black)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        ) {
            HighContrastFocusButton(
                label = "Collection",
                onClick = onOpenCollection,
                modifier = Modifier.fillMaxWidth(),
            )
            HighContrastFocusButton(
                label = "Appearance",
                onClick = onOpenAppearance,
                modifier = Modifier.fillMaxWidth(),
            )
            HighContrastFocusButton(
                label = "Playback",
                onClick = onOpenPlayback,
                modifier = Modifier.fillMaxWidth(),
            )
            HighContrastFocusButton(
                label = "Ambient and System",
                onClick = onOpenAmbient,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
