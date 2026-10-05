package com.jedon.kellikanvas

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jedon.kellikanvas.catalog.CatalogIds
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.SourceProfileKind
import com.jedon.kellikanvas.catalog.preferences.AppPreferencesState
import com.jedon.kellikanvas.diagnostics.ConnectivityTestRunner
import com.jedon.kellikanvas.diagnostics.DiagnosticsScreen
import com.jedon.kellikanvas.diagnostics.RootRestoreStatus
import com.jedon.kellikanvas.diagnostics.appConnectivityChecks
import com.jedon.kellikanvas.feature.collection.BootstrapResult
import com.jedon.kellikanvas.feature.collection.CollectionHubController
import com.jedon.kellikanvas.feature.collection.CollectionHubScreen
import com.jedon.kellikanvas.feature.collection.DlnaSetupController
import com.jedon.kellikanvas.feature.collection.DlnaSetupScreen
import com.jedon.kellikanvas.feature.collection.HouseholdNasBootstrap
import com.jedon.kellikanvas.feature.collection.RetiredSourceProfile
import com.jedon.kellikanvas.feature.collection.SmbSetupController
import com.jedon.kellikanvas.feature.collection.SmbSetupScreen
import com.jedon.kellikanvas.feature.settings.AmbientSettingsScreen
import com.jedon.kellikanvas.feature.settings.AppearanceSettingsScreen
import com.jedon.kellikanvas.feature.settings.PlaybackSettingsScreen
import com.jedon.kellikanvas.feature.settings.UpdateCheckUiState
import com.jedon.kellikanvas.feature.setup.SafSetupController
import com.jedon.kellikanvas.feature.setup.SafSetupScreen
import com.jedon.kellikanvas.feature.slideshow.SimpleSlideshowScreen
import com.jedon.kellikanvas.google.GoogleSetupScreen
import com.jedon.kellikanvas.home.HomeScreen
import com.jedon.kellikanvas.home.PhotosBootstrapUi
import com.jedon.kellikanvas.logging.DiagLog
import com.jedon.kellikanvas.logging.diagnosticSummary
import com.jedon.kellikanvas.model.AppPreferences
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.nas.TailscaleHosts
import com.jedon.kellikanvas.nas.openTailscale
import com.jedon.kellikanvas.platform.update.AndroidCheckTimestampStore
import com.jedon.kellikanvas.security.CredentialReadResult
import com.jedon.kellikanvas.shell.ShellRoute
import com.jedon.kellikanvas.shell.ShellStartup
import com.jedon.kellikanvas.source.SourceAdapter
import com.jedon.kellikanvas.source.dlna.DlnaProfile
import com.jedon.kellikanvas.source.saf.SafProfile
import com.jedon.kellikanvas.source.saf.SafTreeGrant
import com.jedon.kellikanvas.source.smb.SmbCredentials
import com.jedon.kellikanvas.source.smb.SmbProfile
import com.jedon.kellikanvas.system.SystemScreen
import com.jedon.kellikanvas.ui.PhoneMaterialTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.charset.StandardCharsets

private const val TAG = "KelliKanvasNavHost"

private object ShellRoutes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val COLLECTION = "collection"
    const val DLNA_SETUP = "dlna_setup"
    const val SMB_SETUP = "smb_setup"
    const val SLIDESHOW = "slideshow"
    const val APPEARANCE = "appearance"
    const val PLAYBACK = "playback"
    const val AMBIENT = "ambient"
    const val SYSTEM = "system"
    const val ACCOUNT = "account"
    const val DIAGNOSTICS = "diagnostics"
    const val CONNECTORS = "connectors"
    const val CONNECTOR_SETUP = "connector_setup/{provider}/{profileId}"
    const val GOOGLE_SETUP = "google_setup/{kind}/{profileId}"
}

internal data class ShellState(
    val route: ShellRoute,
    val collectionLabel: String = "",
    val roots: List<SelectedRoot> = emptyList(),
    val adapters: Map<SourceProfileId, SourceAdapter> = emptyMap(),
    val loadError: String? = null,
    /** User-visible source problems (e.g. "Household NAS needs reconnecting"), shown on Home. */
    val sourceNotices: List<String> = emptyList(),
    /** Per-profile adapter restore outcomes, shown on the Diagnostics screen. */
    val restoreStatuses: List<RootRestoreStatus> = emptyList(),
    /** DarklingNAS was unreachable and the Tailscale VPN is off. */
    val tailscalePrompt: Boolean = false,
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
    var collectionRevision by remember { mutableIntStateOf(0) }
    var bootstrapUi by remember { mutableStateOf(PhotosBootstrapUi.Idle) }
    var bootstrapError by remember { mutableStateOf<String?>(null) }
    var tailscalePrompt by remember { mutableStateOf(false) }
    var bootstrapAttempt by remember { mutableIntStateOf(0) }
    var autoStartSlideshowToken by remember { mutableIntStateOf(0) }
    var playlistRootFailures by remember { mutableStateOf<List<String>>(emptyList()) }

    // Playlist failures were computed against the previous shell state; drop them on
    // reload so Home doesn't keep showing stale root errors after a reconnect.
    suspend fun reloadShellState() {
        shellState = loadShellState(container)
        playlistRootFailures = emptyList()
    }

    LaunchedEffect(container) {
        container.preferences.preferences.collect { preferences = it }
    }

    LaunchedEffect(container) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            try {
                if (container.cloudAccount.sync()) {
                    reloadShellState()
                    collectionRevision++
                    container.cloudAccount.syncStatus.value = "Account settings and connections synced."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Preserve local edits on conflicts and network failures; Account offers explicit restore.
                container.cloudAccount.syncStatus.value = "Account sync needs attention. Check your connection or restore the account before saving again."
            }
        }
    }

    LaunchedEffect(container) {
        while (true) {
            try {
                container.cloudAccount.browseFromPhone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* Offline or revoked: retry without exposing provider details. */ }
            kotlinx.coroutines.delay(3_000)
        }
    }

    LaunchedEffect(container, bootstrapAttempt) {
        val current = shellState ?: loadShellState(container).also { shellState = it }
        val allRoots =
            runCatching {
                container.database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID)
            }.getOrDefault(current.roots)
        val shouldBootstrap =
            current.adapters.isEmpty() ||
                HouseholdNasBootstrap.needsHouseholdRootReplace(allRoots)
        if (!shouldBootstrap) {
            bootstrapUi = PhotosBootstrapUi.Idle
            bootstrapError = null
            tailscalePrompt = current.tailscalePrompt
            return@LaunchedEffect
        }
        val wasEmpty = allRoots.isEmpty()
        bootstrapUi = PhotosBootstrapUi.Connecting
        bootstrapError = null
        val result = runCatching { householdBootstrap(container).ensurePhotosCollection() }
            .getOrElse { failure ->
                DiagLog.e(TAG, "Household bootstrap crashed", failure)
                BootstrapResult.Failed(failure.message ?: "Bootstrap failed")
            }
        when (result) {
            is BootstrapResult.Success -> {
                DiagLog.i(TAG, "Bootstrap added: ${result.sources}")
                reloadShellState()
                collectionRevision++
                bootstrapUi = PhotosBootstrapUi.Idle
                tailscalePrompt = shellState?.tailscalePrompt == true
                // Auto-start on first empty connect, or when replacing stale household roots.
                if (wasEmpty || HouseholdNasBootstrap.needsHouseholdRootReplace(allRoots)) {
                    autoStartSlideshowToken++
                }
            }
            is BootstrapResult.Failed -> {
                // Keep existing playable roots usable when a re-bootstrap fails.
                if (current.roots.isNotEmpty() && current.adapters.isNotEmpty()) {
                    bootstrapUi = PhotosBootstrapUi.Idle
                    bootstrapError = null
                    tailscalePrompt = current.tailscalePrompt
                } else {
                    bootstrapUi = PhotosBootstrapUi.Failed
                    bootstrapError = result.message
                    tailscalePrompt = container.nasHostResolver.lastResolution == null &&
                        !tailscaleIsConnected(container)
                }
            }
        }
    }

    val state = shellState
    if (state == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "Loading…", color = MaterialTheme.colorScheme.onBackground)
        }
        return
    }

    val updateAppPreferences: ((AppPreferences) -> AppPreferences) -> Unit = { transform ->
        scope.launch {
            container.preferences.update { prefs ->
                prefs.copy(appPreferences = transform(prefs.appPreferences))
            }
        }
    }
    val updateReducedMotion: ((Boolean) -> Boolean) -> Unit = { transform ->
        scope.launch {
            container.preferences.update { prefs ->
                prefs.copy(reducedMotion = transform(prefs.reducedMotion))
            }
        }
    }

    fun openGoogle(kind: SourceKind, id: SourceProfileId? = null) {
        navController.navigate("google_setup/${kind.name}/${id?.value ?: "new"}")
    }
    fun reconnectGoogle(id: SourceProfileId) {
        scope.launch {
            val kind = (container.database.sourceProfiles.get(id)?.kind as? SourceProfileKind.Known)?.value
            if (kind == SourceKind.GOOGLE_DRIVE || kind == SourceKind.GOOGLE_PHOTOS) {
                openGoogle(kind, id)
            } else {
                container.connectorStore.read(id)?.let { navController.navigate("connector_setup/${it.provider.name}/${id.value}") }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = ShellRoutes.HOME,
        modifier = modifier,
    ) {
        composable(ShellRoutes.HOME) {
            val homeState = shellState ?: return@composable
            val canStartSlideshow = homeState.roots.isNotEmpty() && homeState.adapters.isNotEmpty()
            val context = LocalContext.current
            val controller = remember(container.database, context) {
                CollectionHubController(container.database) { retired ->
                    releaseRetiredSource(context, container, retired)
                }
            }
            var collectionState by remember { mutableStateOf(CollectionScreenState()) }
            LaunchedEffect(controller, collectionRevision) {
                collectionState = loadCollectionScreenState(container, controller)
            }
            val updateState = container.updateCheckController?.state?.collectAsState()?.value
            val updateAvailableVersion =
                (updateState as? UpdateCheckUiState.UpdateAvailable)?.versionName
            HomeScreen(
                adapters = homeState.adapters,
                collectionLabel = homeState.collectionLabel.ifBlank { "KelliKanvas" },
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
                onOpenSystem = { navController.navigate(ShellRoutes.SYSTEM) },
                onOpenDiagnostics = { navController.navigate(ShellRoutes.DIAGNOSTICS) },
                onAddLocalFolder = { navController.navigate(ShellRoutes.SETUP) },
                onAddQnap = { navController.navigate(ShellRoutes.DLNA_SETUP) },
                onConnectHouseholdNas = { navController.navigate(ShellRoutes.SMB_SETUP) },
                onAddGoogleDrive = { openGoogle(SourceKind.GOOGLE_DRIVE) },
                onAddGooglePhotos = { openGoogle(SourceKind.GOOGLE_PHOTOS) },
                onReconnectGoogle = ::reconnectGoogle,
                onBrowseConnectors = { navController.navigate(ShellRoutes.CONNECTORS) },
                onRemoveRoot = { root ->
                    scope.launch {
                        runCatching { controller.removeRoot(root) }
                            .onFailure { DiagLog.e(TAG, "removeRoot failed", it) }
                        reloadShellState()
                        collectionRevision++
                        collectionState = loadCollectionScreenState(container, controller)
                    }
                },
                onUpdateHomeControl = { control ->
                    scope.launch {
                        container.preferences.update { it.copy(lastHomeControl = control) }
                    }
                },
                bootstrapUi = bootstrapUi,
                bootstrapError = bootstrapError,
                onRetryBootstrap = { bootstrapAttempt++ },
                collectionLoadError = collectionState.loadError ?: homeState.loadError,
                autoStartSlideshowToken = autoStartSlideshowToken,
                onAutoStartSlideshowConsumed = { autoStartSlideshowToken = 0 },
                updateAvailableVersion = updateAvailableVersion,
                sourceNotices = (homeState.sourceNotices + playlistRootFailures).distinct(),
                tailscalePrompt = tailscalePrompt || homeState.tailscalePrompt,
                onOpenTailscale = { openTailscale(context) },
            )
        }
        composable(ShellRoutes.COLLECTION) {
            val context = LocalContext.current
            val controller = remember(container.database, context) {
                CollectionHubController(container.database) { retired ->
                    releaseRetiredSource(context, container, retired)
                }
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
                    onConnectHouseholdNas = { navController.navigate(ShellRoutes.SMB_SETUP) },
                    onAddGoogleDrive = { openGoogle(SourceKind.GOOGLE_DRIVE) },
                    onAddGooglePhotos = { openGoogle(SourceKind.GOOGLE_PHOTOS) },
                    onReconnectGoogle = ::reconnectGoogle,
                    onBrowseConnectors = { navController.navigate(ShellRoutes.CONNECTORS) },
                    onRemoveRoot = { root ->
                        scope.launch {
                            runCatching { controller.removeRoot(root) }
                                .onFailure { DiagLog.e(TAG, "removeRoot failed", it) }
                            reloadShellState()
                            collectionState = loadCollectionScreenState(container, controller)
                        }
                    },
                    onBack = { navController.popBackStack() },
                    loadError = collectionState.loadError,
                )
            }
        }
        composable(ShellRoutes.CONNECTORS) {
            com.jedon.kellikanvas.connectors.ConnectorCatalogScreen(
                onChoose = { provider -> navController.navigate("connector_setup/${provider.name}/new") },
                onBack = { navController.popBackStack() },
            )
        }
        composable(ShellRoutes.CONNECTOR_SETUP) { entry ->
            val provider = com.jedon.kellikanvas.source.connected.PhotoConnector.entries.firstOrNull { it.name == entry.arguments?.getString("provider") } ?: return@composable
            val profileId = entry.arguments?.getString("profileId")?.takeUnless { it == "new" }?.let(::SourceProfileId)
            com.jedon.kellikanvas.connectors.ConnectorSetupScreen(
                container,
                provider,
                profileId,
                onFinished = {
                    scope.launch {
                        reloadShellState()
                        collectionRevision++
                        navController.navigate(ShellRoutes.COLLECTION) {
                            popUpTo(ShellRoutes.HOME) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onBack = { navController.popBackStack() },
                onHostedPhoneSetup = { navController.navigate(ShellRoutes.ACCOUNT) },
            )
        }
        composable(ShellRoutes.GOOGLE_SETUP) { entry ->
            val kind = if (entry.arguments?.getString("kind") == SourceKind.GOOGLE_DRIVE.name) SourceKind.GOOGLE_DRIVE else SourceKind.GOOGLE_PHOTOS
            val profileId = entry.arguments?.getString("profileId")?.takeUnless { it == "new" }?.let(::SourceProfileId)
            GoogleSetupScreen(
                container.googleSources,
                container.database,
                kind,
                profileId,
                onFinished = {
                    scope.launch {
                        reloadShellState()
                        collectionRevision++
                        navController.navigate(ShellRoutes.COLLECTION) {
                            popUpTo(ShellRoutes.HOME) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(ShellRoutes.SETUP) {
            PhoneMaterialTheme {
                SafSetupScreen(
                    controller = SafSetupController(container.database),
                    onFinished = {
                        scope.launch {
                            reloadShellState()
                            collectionRevision++
                            navController.navigate(ShellRoutes.COLLECTION) {
                                popUpTo(ShellRoutes.HOME) { inclusive = false }
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
        }
        composable(ShellRoutes.DLNA_SETUP) {
            PhoneMaterialTheme {
                DlnaSetupScreen(
                    controller = DlnaSetupController(
                        database = container.database,
                        discoverProfiles = {
                            container.dlnaDiscovery().setupNamed().map {
                                it.friendlyName to it.profile
                            }
                        },
                        resolveManual = { container.dlnaManualResolver().resolve(it) },
                        resolveBuiltIn = {
                            container.dlnaManualResolver().resolveBuiltIn(
                                preferredHosts = listOfNotNull(container.nasHostResolver.resolve()?.host),
                            )
                        },
                        adapterFactory = { container.dlnaAdapter(it) },
                    ),
                    onFinished = {
                        scope.launch {
                            reloadShellState()
                            collectionRevision++
                            navController.navigate(ShellRoutes.COLLECTION) {
                                popUpTo(ShellRoutes.HOME) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(ShellRoutes.SMB_SETUP) {
            val context = LocalContext.current
            PhoneMaterialTheme {
                SmbSetupScreen(
                    onHostedPhoneSetup = { navController.navigate(ShellRoutes.ACCOUNT) },
                    controller =
                    SmbSetupController(
                        database = container.database,
                        credentialVault = container.credentialVault,
                        householdUsername = container.householdSmbUsername(),
                        householdPassword = container.householdSmbPassword(),
                        adapterFactory = { profile, credentials ->
                            container.smbAdapter(profile, credentials)
                        },
                        resolvePreferredHost = { container.nasHostResolver.resolve()?.host },
                    ),
                    onFinished = {
                        scope.launch {
                            reloadShellState()
                            collectionRevision++
                            navController.navigate(ShellRoutes.COLLECTION) {
                                popUpTo(ShellRoutes.HOME) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    },
                    onBack = { navController.popBackStack() },
                    isTailscaleConnected = { tailscaleIsConnected(container) },
                    onOpenTailscale = { openTailscale(context) },
                )
            }
        }
        composable(ShellRoutes.APPEARANCE) {
            AppearanceSettingsScreen(
                preferences = preferences,
                onUpdatePreferences = updateAppPreferences,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ShellRoutes.PLAYBACK) {
            PlaybackSettingsScreen(
                preferences = preferences,
                onUpdatePreferences = updateAppPreferences,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ShellRoutes.AMBIENT) {
            AmbientSettingsScreen(
                preferences = preferences,
                onUpdatePreferences = updateAppPreferences,
                onUpdateReducedMotion = updateReducedMotion,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ShellRoutes.SYSTEM) {
            SystemScreen(
                onBack = { navController.popBackStack() },
                onOpenDiagnostics = { navController.navigate(ShellRoutes.DIAGNOSTICS) },
                updateCheckController = container.updateCheckController,
                onOpenAccount = { navController.navigate(ShellRoutes.ACCOUNT) },
            )
        }
        composable(ShellRoutes.ACCOUNT) {
            PhoneMaterialTheme {
                com.jedon.kellikanvas.account.AccountScreen(container.cloudAccount, onChanged = {
                    scope.launch {
                        reloadShellState()
                        collectionRevision++
                    }
                }, onBack = { navController.popBackStack() })
            }
        }
        composable(ShellRoutes.DIAGNOSTICS) {
            val diagnosticsState = shellState ?: return@composable
            val context = LocalContext.current
            // Keyed on container only: a shell-state reload while a test is running must
            // not orphan the runner (results reset, single-flight broken). buildChecks
            // reads the latest shell state at run time instead.
            val connectivityRunner = remember(container) {
                ConnectivityTestRunner(
                    buildChecks = {
                        val current = checkNotNull(shellState) { "Shell state not loaded" }
                        appConnectivityChecks(
                            container = container,
                            roots = current.roots,
                            adapters = current.adapters,
                            restoreStatuses = current.restoreStatuses,
                        )
                    },
                )
            }
            // SharedPreferences hits disk on first access; keep it off the main thread.
            var lastUpdateCheckMillis by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(context) {
                lastUpdateCheckMillis = withContext(Dispatchers.IO) {
                    AndroidCheckTimestampStore(context).lastCheckMillis()
                }
            }
            DiagnosticsScreen(
                onBack = { navController.popBackStack() },
                restoreStatuses = diagnosticsState.restoreStatuses,
                roots = diagnosticsState.roots,
                connectivityRunner = connectivityRunner,
                updateCheckController = container.updateCheckController,
                lastUpdateCheckMillis = lastUpdateCheckMillis,
                nasResolution = container.nasHostResolver.lastResolution,
                tailscaleConnected = tailscaleIsConnected(container),
                onOpenTailscale = { openTailscale(context) },
            )
        }
        composable(ShellRoutes.SLIDESHOW) {
            val slideshowState = shellState ?: return@composable
            if (slideshowState.adapters.isEmpty() || slideshowState.roots.isEmpty()) {
                Text(text = "No photos in this collection")
            } else {
                // A phone selection replaces the running playlist as well as the home gallery.
                androidx.compose.runtime.key(slideshowState.adapters, slideshowState.roots) {
                    SimpleSlideshowScreen(
                        adapters = slideshowState.adapters,
                        roots = slideshowState.roots,
                        slideDurationMillis = preferences.appPreferences.slideDurationMillis,
                        transitionType = preferences.appPreferences.transitionType,
                        transitionDurationMillis = preferences.appPreferences.transitionDurationMillis,
                        onExit = { navController.popBackStack() },
                        onRootFailures = { playlistRootFailures = it },
                    )
                }
            }
        }
    }
}

private data class CollectionScreenState(
    val roots: List<SelectedRoot> = emptyList(),
    val sourceLabels: Map<SourceProfileId, String> = emptyMap(),
    val loadError: String? = null,
)

private fun householdBootstrap(container: AppContainer): HouseholdNasBootstrap {
    val smb = SmbSetupController(
        database = container.database,
        credentialVault = container.credentialVault,
        householdUsername = container.householdSmbUsername(),
        householdPassword = container.householdSmbPassword(),
        adapterFactory = { profile, credentials ->
            container.smbAdapter(profile, credentials)
        },
        resolvePreferredHost = { container.nasHostResolver.resolve()?.host },
    )
    val dlna = DlnaSetupController(
        database = container.database,
        discoverProfiles = {
            container.dlnaDiscovery().setupNamed().map {
                it.friendlyName to it.profile
            }
        },
        resolveManual = { container.dlnaManualResolver().resolve(it) },
        resolveBuiltIn = {
            container.dlnaManualResolver().resolveBuiltIn(
                preferredHosts = listOfNotNull(container.nasHostResolver.resolve()?.host),
            )
        },
        adapterFactory = { container.dlnaAdapter(it) },
    )
    return HouseholdNasBootstrap(
        smb = smb,
        dlna = dlna,
        hasHouseholdSmbCredentials = {
            container.householdSmbUsername().isNotBlank() &&
                container.householdSmbPassword().isNotEmpty()
        },
        recordKnownGoodIp = container.nasHostResolver::recordKnownGoodIp,
    )
}

private suspend fun loadCollectionScreenState(
    container: AppContainer,
    controller: CollectionHubController,
): CollectionScreenState = try {
    val roots = controller.listRoots()
    val sourceLabels = roots
        .map(SelectedRoot::profileId)
        .distinct()
        .associateWith { profileId ->
            when {
                container.database.googleConnections.get(profileId) != null ->
                    container.database.sourceProfiles.get(profileId)?.displayName ?: "Google"
                container.database.safConnections.get(profileId) != null -> "Local"
                container.database.smbConnections.get(profileId) != null ->
                    container.database.smbConnections.get(profileId)
                        ?.displayName
                        ?.ifBlank { "SMB" }
                        ?: "SMB"
                else -> container.database.dlnaConnections.get(profileId)
                    ?.displayName
                    ?.ifBlank { "QNAP" }
                    ?: "Unknown"
            }
        }
    CollectionScreenState(roots, sourceLabels)
} catch (failure: Exception) {
    DiagLog.e(TAG, "loadCollectionScreenState failed", failure)
    CollectionScreenState(loadError = "Could not load collection. ${failure.message?.take(80) ?: ""}".trim())
}

internal suspend fun loadShellState(container: AppContainer): ShellState {
    return try {
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
        val sourceNotices = mutableListOf<String>()
        val restoreStatuses = mutableListOf<RootRestoreStatus>()
        var tailscalePrompt = false
        for (profileId in roots.map(SelectedRoot::profileId).distinct()) {
            try {
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
                        restoreStatuses += RootRestoreStatus(
                            profileId = profileId,
                            label = "Local folder",
                            kind = SourceKind.SAF,
                            restored = true,
                        )
                    } else {
                        restoreStatuses += RootRestoreStatus(
                            profileId = profileId,
                            label = "Local folder",
                            kind = SourceKind.SAF,
                            restored = false,
                            reason = "persisted folder permission (SAF grant) missing",
                        )
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
                    restoreStatuses += RootRestoreStatus(
                        profileId = profileId,
                        label = connection.displayName.ifBlank { "QNAP" },
                        kind = SourceKind.DLNA,
                        restored = true,
                    )
                }
                database.smbConnections.get(profileId)?.let { connection ->
                    val passwordChars = readSmbPassword(container, profileId)
                    if (passwordChars == null) {
                        DiagLog.w(
                            TAG,
                            "SMB vault password missing for ${profileId.value} (${connection.displayName}); " +
                                "adapter not restored",
                        )
                        sourceNotices += "Household NAS needs reconnecting"
                        restoreStatuses += RootRestoreStatus(
                            profileId = profileId,
                            label = connection.displayName.ifBlank { "SMB" },
                            kind = SourceKind.SMB,
                            restored = false,
                            reason = "SMB password missing from vault; reconnect needed",
                        )
                        return@let
                    }
                    val household = TailscaleHosts.isHouseholdNasHost(connection.host)
                    val resolvedHost = if (household) resolveHouseholdHost(container) else null
                    if (household && resolvedHost == null) {
                        if (tailscaleIsConnected(container)) {
                            sourceNotices += "DarklingNAS didn't answer. Tailscale is connected."
                        } else {
                            tailscalePrompt = true
                        }
                    }
                    val profile =
                        SmbProfile(
                            id = profileId,
                            host = resolvedHost ?: connection.host,
                            port = connection.port,
                            share = connection.share,
                            domain = connection.domain,
                            username = connection.username,
                        )
                    val credentials =
                        SmbCredentials(
                            username = connection.username,
                            password = passwordChars,
                            domain = connection.domain,
                        )
                    adapters[profileId] = container.smbAdapter(profile, credentials)
                    restoreStatuses += RootRestoreStatus(
                        profileId = profileId,
                        label = connection.displayName.ifBlank { "SMB" },
                        kind = SourceKind.SMB,
                        restored = true,
                    )
                }
                database.googleConnections.get(profileId)?.let { connection ->
                    val profile = database.sourceProfiles.get(profileId)
                    val kind = (profile?.kind as? SourceProfileKind.Known)?.value
                    if (kind == SourceKind.GOOGLE_DRIVE || kind == SourceKind.GOOGLE_PHOTOS) {
                        val adapter = container.googleSources.restore(connection, kind)
                        if (adapter != null) {
                            adapters[profileId] = adapter
                        } else {
                            sourceNotices += "${profile.displayName} needs reconnecting from Collection"
                        }
                        restoreStatuses += RootRestoreStatus(
                            profileId,
                            profile.displayName,
                            kind,
                            adapter != null,
                            if (adapter == null) "Google connection needs reconnecting" else null,
                        )
                    }
                }
                val connectedProfile = database.sourceProfiles.get(profileId)
                val connectedKind = (connectedProfile?.kind as? SourceProfileKind.Known)?.value
                if (com.jedon.kellikanvas.source.connected.PhotoConnector.entries.any { it.kind == connectedKind }) {
                    val config = container.connectorStore.read(profileId)
                    val adapter = config?.takeIf { it.provider.kind == connectedKind }?.let {
                        com.jedon.kellikanvas.source.connected.connectedPhotoSource(profileId, it, container.httpClient)
                    }
                    if (adapter != null) adapters[profileId] = adapter else sourceNotices += "${connectedProfile?.displayName} needs reconnecting from Collection"
                    restoreStatuses += RootRestoreStatus(profileId, connectedProfile?.displayName.orEmpty(), connectedKind, adapter != null, if (adapter == null) "Connection needs reconnecting" else null)
                }
                if (restoreStatuses.none { it.profileId == profileId }) {
                    restoreStatuses += RootRestoreStatus(
                        profileId = profileId,
                        label = "Unknown source",
                        kind = null,
                        restored = false,
                        reason = "no connection record found",
                    )
                }
            } catch (failure: Exception) {
                DiagLog.e(TAG, "Failed to restore adapter for $profileId", failure)
                if (restoreStatuses.none { it.profileId == profileId }) {
                    restoreStatuses += RootRestoreStatus(
                        profileId = profileId,
                        label = "Source ${profileId.value}",
                        kind = null,
                        restored = false,
                        reason = failure.diagnosticSummary(),
                    )
                }
            }
        }
        val playableRoots = roots.filter { it.profileId in adapters }
        ShellState(
            route = ShellRoute.Home,
            collectionLabel = activeCollection.label,
            roots = playableRoots,
            adapters = adapters,
            loadError = if (playableRoots.isEmpty() && roots.isNotEmpty()) {
                "Saved photo folders could not be opened. Open Collection to reconnect."
            } else {
                null
            },
            sourceNotices = sourceNotices.distinct(),
            restoreStatuses = restoreStatuses.toList(),
            tailscalePrompt = tailscalePrompt,
        )
    } catch (failure: Exception) {
        DiagLog.e(TAG, "loadShellState failed", failure)
        ShellState(
            route = ShellRoute.Home,
            collectionLabel = "KelliKanvas",
            loadError = "Could not load photos. ${failure.message?.take(80) ?: "Try again."}",
        )
    }
}

private suspend fun releaseRetiredSource(
    context: Context,
    container: AppContainer,
    retired: RetiredSourceProfile,
) {
    retired.googleConnection?.let { connection ->
        try {
            container.googleSources.disconnect(connection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DiagLog.w(TAG, "Google device removal could not be completed; local connection removed")
        }
    }
    container.credentialVault.remove(retired.profileId)
    val treeUri = retired.safTreeUri ?: return
    try {
        context.contentResolver.releasePersistableUriPermission(
            treeUri.toUri(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    } catch (_: SecurityException) {
        // The grant may already have been released.
    }
}

private suspend fun resolveHouseholdHost(container: AppContainer): String? = try {
    container.nasHostResolver.resolve()?.host
} catch (failure: CancellationException) {
    throw failure
} catch (failure: Exception) {
    DiagLog.w(TAG, "NAS host refresh failed", failure)
    null
}

private fun tailscaleIsConnected(container: AppContainer): Boolean = try {
    container.tailscaleNas.isConnected()
} catch (failure: Exception) {
    DiagLog.w(TAG, "Tailscale status check failed", failure)
    false
}

private fun readSmbPassword(
    container: AppContainer,
    profileId: SourceProfileId,
): CharArray? = when (val result = container.credentialVault.read(profileId)) {
    is CredentialReadResult.Present -> {
        result.use { present ->
            val bytes = present.secret.copyBytes()
            try {
                String(bytes, StandardCharsets.UTF_8).toCharArray()
            } finally {
                bytes.fill(0)
            }
        }
    }
    CredentialReadResult.Missing, CredentialReadResult.RequiresReentry -> null
}
