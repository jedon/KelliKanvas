package com.jedon.kellikanvas

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.preferences.AppPreferencesState
import com.jedon.kellikanvas.feature.collection.CollectionHubController
import com.jedon.kellikanvas.feature.collection.CollectionHubScreen
import com.jedon.kellikanvas.feature.collection.DlnaSetupController
import com.jedon.kellikanvas.feature.collection.DlnaSetupScreen
import com.jedon.kellikanvas.feature.setup.SafSetupController
import com.jedon.kellikanvas.feature.setup.SafSetupScreen
import com.jedon.kellikanvas.feature.slideshow.SimpleSlideshowScreen
import com.jedon.kellikanvas.home.HomeScreen
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.settings.SettingsPlaceholderScreen
import com.jedon.kellikanvas.shell.ShellRoute
import com.jedon.kellikanvas.shell.ShellStartup
import com.jedon.kellikanvas.source.SourceAdapter
import com.jedon.kellikanvas.source.dlna.DlnaProfile
import com.jedon.kellikanvas.source.saf.SafProfile
import com.jedon.kellikanvas.source.saf.SafTreeGrant
import com.jedon.kellikanvas.ui.PhoneMaterialTheme
import kotlinx.coroutines.launch
import java.net.URI

private object ShellRoutes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val COLLECTION = "collection"
    const val DLNA_SETUP = "dlna_setup"
    const val SLIDESHOW = "slideshow"
    const val APPEARANCE = "appearance"
    const val PLAYBACK = "playback"
    const val AMBIENT = "ambient"
}

private data class ShellState(
    val route: ShellRoute,
    val collectionLabel: String = "",
    val roots: List<SelectedRoot> = emptyList(),
    val adapters: Map<SourceProfileId, SourceAdapter> = emptyMap(),
)

@Suppress("ktlint:standard:function-naming")
@Composable
fun KelliKanvasNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    var shellState by remember { mutableStateOf<ShellState?>(null) }
    var preferences by remember { mutableStateOf(AppPreferencesState()) }
    var collectionRevision by remember { mutableStateOf(0) }

    LaunchedEffect(container) {
        shellState = loadShellState(container)
    }
    LaunchedEffect(container) {
        container.preferences.preferences.collect { preferences = it }
    }

    val state = shellState
    if (state == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "Loading…", color = MaterialTheme.colorScheme.onBackground)
        }
        return
    }

    NavHost(
        navController = navController,
        startDestination = ShellRoutes.HOME,
        modifier = modifier,
    ) {
        composable(ShellRoutes.HOME) {
            val homeState = shellState ?: return@composable
            val canStartSlideshow = homeState.roots.isNotEmpty() && homeState.adapters.isNotEmpty()
            val controller = remember(container.database) {
                CollectionHubController(container.database)
            }
            var collectionState by remember { mutableStateOf(CollectionScreenState()) }
            LaunchedEffect(controller, collectionRevision) {
                collectionState = loadCollectionScreenState(container, controller)
            }
            HomeScreen(
                collectionLabel = homeState.collectionLabel,
                canStartSlideshow = canStartSlideshow,
                roots = collectionState.roots,
                sourceLabels = collectionState.sourceLabels,
                onStartSlideshow = {
                    if (canStartSlideshow) {
                        navController.navigate(ShellRoutes.SLIDESHOW)
                    }
                },
                onOpenAppearance = { navController.navigate(ShellRoutes.APPEARANCE) },
                onOpenPlayback = { navController.navigate(ShellRoutes.PLAYBACK) },
                onOpenAmbient = { navController.navigate(ShellRoutes.AMBIENT) },
                onAddLocalFolder = { navController.navigate(ShellRoutes.SETUP) },
                onAddQnap = { navController.navigate(ShellRoutes.DLNA_SETUP) },
                onRemoveRoot = { root ->
                    scope.launch {
                        controller.removeRoot(root)
                        shellState = loadShellState(container)
                        collectionRevision++
                        collectionState = loadCollectionScreenState(container, controller)
                    }
                },
                onUpdateHomeControl = { control ->
                    scope.launch {
                        container.preferences.update { it.copy(lastHomeControl = control) }
                    }
                },
            )
        }
        composable(ShellRoutes.COLLECTION) {
            val controller = remember(container.database) {
                CollectionHubController(container.database)
            }
            var collectionState by remember { mutableStateOf(CollectionScreenState()) }
            LaunchedEffect(controller, collectionRevision) {
                collectionState = loadCollectionScreenState(container, controller)
            }
            PhoneMaterialTheme {
                CollectionHubScreen(
                    roots = collectionState.roots,
                    sourceLabels = collectionState.sourceLabels,
                    onAddLocalFolder = { navController.navigate(ShellRoutes.SETUP) },
                    onAddQnap = { navController.navigate(ShellRoutes.DLNA_SETUP) },
                    onRemoveRoot = { root ->
                        scope.launch {
                            controller.removeRoot(root)
                            shellState = loadShellState(container)
                            collectionState = loadCollectionScreenState(container, controller)
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(ShellRoutes.SETUP) {
            SafSetupScreen(
                controller = SafSetupController(container.database),
                onFinished = {
                    scope.launch {
                        shellState = loadShellState(container)
                        collectionRevision++
                        navController.navigate(ShellRoutes.COLLECTION) {
                            popUpTo(ShellRoutes.COLLECTION) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onOpenMenu = {
                    if (!navController.popBackStack(ShellRoutes.COLLECTION, inclusive = false)) {
                        navController.navigate(ShellRoutes.HOME) {
                            popUpTo(ShellRoutes.HOME) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable(ShellRoutes.DLNA_SETUP) {
            DlnaSetupScreen(
                controller = DlnaSetupController(
                    database = container.database,
                    discoverProfiles = {
                        container.dlnaDiscovery().setupNamed().map {
                            it.friendlyName to it.profile
                        }
                    },
                    resolveManual = { container.dlnaManualResolver().resolve(it) },
                    adapterFactory = { container.dlnaAdapter(it) },
                ),
                onFinished = {
                    scope.launch {
                        shellState = loadShellState(container)
                        collectionRevision++
                        navController.navigate(ShellRoutes.COLLECTION) {
                            popUpTo(ShellRoutes.COLLECTION) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(ShellRoutes.APPEARANCE) {
            SettingsPlaceholderScreen(
                title = "Appearance",
                body = "Layout, pairing, and transition settings will live here.",
                onBack = { navController.popBackStack() },
            )
        }
        composable(ShellRoutes.PLAYBACK) {
            SettingsPlaceholderScreen(
                title = "Playback",
                body = "Slide timing, order, and resume settings will live here.",
                onBack = { navController.popBackStack() },
            )
        }
        composable(ShellRoutes.AMBIENT) {
            SettingsPlaceholderScreen(
                title = "Ambient and System",
                body = "Brightness, presence timeout, and system options will live here.",
                onBack = { navController.popBackStack() },
            )
        }
        composable(ShellRoutes.SLIDESHOW) {
            val slideshowState = shellState ?: return@composable
            if (slideshowState.adapters.isEmpty() || slideshowState.roots.isEmpty()) {
                Text(text = "No photos in this collection")
            } else {
                SimpleSlideshowScreen(
                    adapters = slideshowState.adapters,
                    roots = slideshowState.roots,
                    slideDurationMillis = preferences.appPreferences.slideDurationMillis,
                    onExit = { navController.popBackStack() },
                )
            }
        }
    }
}

private data class CollectionScreenState(
    val roots: List<SelectedRoot> = emptyList(),
    val sourceLabels: Map<SourceProfileId, String> = emptyMap(),
)

private suspend fun loadCollectionScreenState(
    container: AppContainer,
    controller: CollectionHubController,
): CollectionScreenState {
    val roots = controller.listRoots()
    val sourceLabels = roots
        .map(SelectedRoot::profileId)
        .distinct()
        .associateWith { profileId ->
            when {
                container.database.safConnections.get(profileId) != null -> "Local"
                else -> container.database.dlnaConnections.get(profileId)
                    ?.displayName
                    ?.ifBlank { "QNAP" }
                    ?: "Unknown"
            }
        }
    return CollectionScreenState(roots, sourceLabels)
}

private suspend fun loadShellState(container: AppContainer): ShellState {
    val database = container.database
    val collections = database.collections.list()
    val rootsByCollection = collections.associate { it.id to database.selectedRoots.list(it.id) }
    if (!ShellStartup.hasPlayableRoots(collections, rootsByCollection)) {
        return ShellState(route = ShellRoute.Home, collectionLabel = "KelliKanvas")
    }

    val activeCollection = collections.first { rootsByCollection[it.id].orEmpty().isNotEmpty() }
    val roots = rootsByCollection.getValue(activeCollection.id)
    val resolver = container.contentResolver
    val adapters = linkedMapOf<SourceProfileId, SourceAdapter>()
    for (profileId in roots.map(SelectedRoot::profileId).distinct()) {
        database.safConnections.get(profileId)?.let { connection ->
            val treeUri = connection.treeUri.toUri()
            val hasReadPermission = resolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isReadPermission
            }
            if (hasReadPermission) {
                val grant = SafTreeGrant(
                    treeUri = treeUri,
                    documentId = DocumentsContract.getTreeDocumentId(treeUri),
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
                adapters[profileId] = container.safAdapter(SafProfile(profileId, grant))
            }
        }
        database.dlnaConnections.get(profileId)?.let { connection ->
            val profile = DlnaProfile(
                id = profileId,
                serverUdn = connection.serverUdn,
                descriptionLocation = URI(connection.descriptionLocation),
                controlUrl = URI(connection.controlUrl),
                contentDirectoryVersion = connection.contentDirectoryVersion,
            )
            adapters[profileId] = container.dlnaAdapter(profile)
        }
    }
    val playableRoots = roots.filter { it.profileId in adapters }
    return ShellState(
        route = ShellRoute.Home,
        collectionLabel = activeCollection.label,
        roots = playableRoots,
        adapters = adapters,
    )
}
