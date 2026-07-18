package com.jedon.kellikanvas

import android.content.Intent
import android.net.Uri
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.preferences.AppPreferencesState
import com.jedon.kellikanvas.feature.setup.SafSetupController
import com.jedon.kellikanvas.feature.setup.SafSetupScreen
import com.jedon.kellikanvas.feature.slideshow.SimpleSlideshowScreen
import com.jedon.kellikanvas.home.HomeScreen
import com.jedon.kellikanvas.shell.ShellRoute
import com.jedon.kellikanvas.shell.ShellStartup
import com.jedon.kellikanvas.source.saf.SafProfile
import com.jedon.kellikanvas.source.saf.SafTreeGrant
import kotlinx.coroutines.launch

private object ShellRoutes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val SLIDESHOW = "slideshow"
}

private data class ShellState(
    val route: ShellRoute,
    val collectionLabel: String = "",
    val roots: List<SelectedRoot> = emptyList(),
    val adapter: com.jedon.kellikanvas.source.SourceAdapter? = null,
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
        startDestination = if (state.route == ShellRoute.Setup) ShellRoutes.SETUP else ShellRoutes.HOME,
        modifier = modifier,
    ) {
        composable(ShellRoutes.SETUP) {
            SafSetupScreen(
                controller = SafSetupController(container.database),
                onFinished = {
                    scope.launch {
                        shellState = loadShellState(container)
                        navController.navigate(ShellRoutes.HOME) {
                            popUpTo(ShellRoutes.SETUP) { inclusive = true }
                        }
                    }
                },
            )
        }
        composable(ShellRoutes.HOME) {
            val homeState = shellState ?: return@composable
            HomeScreen(
                collectionLabel = homeState.collectionLabel,
                canStartSlideshow = homeState.roots.isNotEmpty(),
                initialFocus = preferences.lastHomeControl,
                onStartSlideshow = {
                    if (homeState.roots.isNotEmpty() && homeState.adapter != null) {
                        navController.navigate(ShellRoutes.SLIDESHOW)
                    }
                },
                onUpdateHomeControl = { control ->
                    scope.launch {
                        container.preferences.update { it.copy(lastHomeControl = control) }
                    }
                },
            )
        }
        composable(ShellRoutes.SLIDESHOW) {
            val slideshowState = shellState ?: return@composable
            val adapter = slideshowState.adapter
            if (adapter == null || slideshowState.roots.isEmpty()) {
                Text(text = "No photos in this collection")
            } else {
                SimpleSlideshowScreen(
                    adapter = adapter,
                    roots = slideshowState.roots,
                    slideDurationMillis = preferences.appPreferences.slideDurationMillis,
                    onExit = { navController.popBackStack() },
                )
            }
        }
    }
}

private suspend fun loadShellState(container: AppContainer): ShellState {
    val database = container.database
    val collections = database.collections.list()
    val rootsByCollection = collections.associate { it.id to database.selectedRoots.list(it.id) }
    val startupRoute = ShellStartup.startRoute(collections, rootsByCollection)
    if (startupRoute == ShellRoute.Setup) return ShellState(route = ShellRoute.Setup)

    val activeCollection = collections.first { rootsByCollection[it.id].orEmpty().isNotEmpty() }
    val roots = rootsByCollection.getValue(activeCollection.id)
    val profileId = roots.first().profileId
    val connection = database.safConnections.get(profileId) ?: return ShellState(ShellRoute.Setup)
    val treeUri = Uri.parse(connection.treeUri)
    val resolver = container.contentResolver
    val hasReadPermission = resolver.persistedUriPermissions.any {
        it.uri == treeUri && it.isReadPermission
    }
    if (!hasReadPermission) return ShellState(ShellRoute.Setup)

    val grant = SafTreeGrant(
        treeUri = treeUri,
        documentId = DocumentsContract.getTreeDocumentId(treeUri),
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
    )
    val profile = SafProfile(profileId, grant)
    return ShellState(
        route = ShellRoute.Home,
        collectionLabel = activeCollection.label,
        roots = roots,
        adapter = container.safAdapter(profile),
    )
}
