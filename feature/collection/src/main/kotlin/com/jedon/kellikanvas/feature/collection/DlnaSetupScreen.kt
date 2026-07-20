package com.jedon.kellikanvas.feature.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.ui.tv.highContrastFocus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("ktlint:standard:function-naming", "LongMethod")
@Composable
fun DlnaSetupScreen(
    controller: DlnaSetupController,
    onFinished: (collectionId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val selectedFolders = remember { mutableStateMapOf<String, BrowseEntry>() }
    var phase by remember { mutableStateOf<DlnaSetupPhase>(DlnaSetupPhase.Idle) }
    var manualHost by remember { mutableStateOf("") }
    var includeDescendants by remember { mutableStateOf(true) }

    fun submit(action: DlnaSetupAction) {
        scope.launch {
            when (action) {
                DlnaSetupAction.Discover -> {
                    phase = DlnaSetupPhase.Discovering
                    runCatching { controller.discover() }
                        .onSuccess { servers ->
                            if (servers.isNotEmpty()) {
                                phase = DlnaSetupPhase.ServerList(servers)
                            } else {
                                runCatching { controller.tryKnownHosts() }
                                    .onSuccess { known ->
                                        phase = DlnaSetupPhase.ServerList(
                                            servers = listOf(known),
                                            statusMessage = "Connected via ${known.matchedHost ?: known.friendlyName}",
                                        )
                                    }
                                    .onFailure {
                                        phase = DlnaSetupPhase.ServerList(emptyList())
                                    }
                            }
                        }
                        .onFailure {
                            runCatching { controller.tryKnownHosts() }
                                .onSuccess { known ->
                                    phase = DlnaSetupPhase.ServerList(
                                        servers = listOf(known),
                                        statusMessage = "Connected via ${known.matchedHost ?: known.friendlyName}",
                                    )
                                }
                                .onFailure {
                                    phase = DlnaSetupPhase.Error(
                                        message = "Could not discover QNAP servers.",
                                        retryAction = action,
                                    )
                                }
                        }
                }

                DlnaSetupAction.TryKnown -> {
                    phase = DlnaSetupPhase.Discovering
                    runCatching { controller.tryKnownHosts() }
                        .onSuccess { known ->
                            phase = DlnaSetupPhase.ServerList(
                                servers = listOf(known),
                                statusMessage = "Connected via ${known.matchedHost ?: known.friendlyName}",
                            )
                        }
                        .onFailure {
                            phase = DlnaSetupPhase.Error(
                                message = "Could not reach any known NAS address.",
                                retryAction = action,
                            )
                        }
                }

                is DlnaSetupAction.Resolve -> {
                    phase = DlnaSetupPhase.Discovering
                    runCatching { controller.resolveHost(action.input) }
                        .onSuccess { server ->
                            selectedFolders.clear()
                            submit(DlnaSetupAction.Browse(server, "0"))
                        }
                        .onFailure {
                            phase = DlnaSetupPhase.Error(
                                message = "Could not connect to that host.",
                                retryAction = action,
                            )
                        }
                }

                is DlnaSetupAction.Browse -> {
                    phase = DlnaSetupPhase.LoadingFolders
                    runCatching {
                        controller.listChildren(
                            profile = action.server.profile,
                            folderObjectId = action.folderObjectId,
                        )
                    }.onSuccess { folders ->
                        val photos =
                            if (action.folderObjectId == "0") {
                                PhotosFolderPicker.findPhotosFolder(folders)
                            } else {
                                null
                            }
                        if (photos != null) {
                            selectedFolders.clear()
                            selectedFolders[photos.objectId] = photos
                            includeDescendants = true
                            phase = DlnaSetupPhase.Confirm(action.server)
                        } else {
                            phase = DlnaSetupPhase.Browsing(
                                server = action.server,
                                folderObjectId = action.folderObjectId,
                                folders = folders,
                            )
                        }
                    }.onFailure { failure ->
                        phase = DlnaSetupPhase.Error(
                            message = dlnaSetupFailureMessage(
                                fallback = "Could not load folders from this server.",
                                failure = failure,
                            ),
                            retryAction = action,
                        )
                    }
                }

                is DlnaSetupAction.Save -> {
                    phase = DlnaSetupPhase.Saving
                    runCatching {
                        controller.saveSelection(
                            profile = action.server.profile,
                            friendlyName = action.server.friendlyName,
                            folders = action.folders,
                        )
                    }.onSuccess(onFinished)
                        .onFailure {
                            phase = DlnaSetupPhase.Error(
                                message = "Could not save the selected folders.",
                                retryAction = action,
                            )
                        }
                }
            }
        }
    }

    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Add QNAP") },
                windowInsets = WindowInsets.statusBars,
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.highContrastFocus(),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val currentPhase = phase) {
                DlnaSetupPhase.Idle -> {
                    Text(
                        text = "Find a QNAP media server on your network.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        onClick = { submit(DlnaSetupAction.Discover) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .highContrastFocus(),
                    ) {
                        Text("Discover servers")
                    }
                    Button(
                        onClick = { submit(DlnaSetupAction.TryKnown) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .highContrastFocus(),
                    ) {
                        Text("Try known NAS")
                    }
                }

                DlnaSetupPhase.Discovering -> ProgressMessage("Discovering servers…")
                DlnaSetupPhase.LoadingFolders -> ProgressMessage("Loading folders…")
                DlnaSetupPhase.Saving -> ProgressMessage("Saving selection…")

                is DlnaSetupPhase.ServerList -> {
                    Text(
                        text = currentPhase.statusMessage
                            ?: if (currentPhase.servers.isEmpty()) {
                                "No servers found. Enter a host name or IP address, or try known NAS."
                            } else {
                                "Choose a server."
                            },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    currentPhase.servers.forEach { server ->
                        Button(
                            onClick = {
                                selectedFolders.clear()
                                submit(DlnaSetupAction.Browse(server, "0"))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .highContrastFocus(),
                        ) {
                            Text(
                                when {
                                    server.matchedHost != null &&
                                        server.matchedHost != server.friendlyName ->
                                        "${server.friendlyName} (${server.matchedHost})"
                                    else -> server.friendlyName
                                },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Host name or IP address") },
                        singleLine = true,
                    )
                    Button(
                        enabled = manualHost.isNotBlank(),
                        onClick = { submit(DlnaSetupAction.Resolve(manualHost)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .highContrastFocus(),
                    ) {
                        Text("Connect")
                    }
                    TextButton(
                        onClick = { submit(DlnaSetupAction.TryKnown) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .highContrastFocus(),
                    ) {
                        Text("Try known NAS")
                    }
                    TextButton(
                        onClick = { submit(DlnaSetupAction.Discover) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .highContrastFocus(),
                    ) {
                        Text("Discover again")
                    }
                }

                is DlnaSetupPhase.Browsing -> {
                    val via = currentPhase.server.matchedHost ?: currentPhase.server.friendlyName
                    Text(
                        text = "Connected via $via",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Select one or more photo folders.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (currentPhase.folders.isEmpty()) {
                        Text("No folders found here.")
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                items = currentPhase.folders,
                                key = BrowseEntry::objectId,
                            ) { folder ->
                                FolderRow(
                                    folder = folder,
                                    selected = selectedFolders.containsKey(folder.objectId),
                                    onSelectedChange = { selected ->
                                        if (selected) {
                                            selectedFolders[folder.objectId] = folder
                                        } else {
                                            selectedFolders.remove(folder.objectId)
                                        }
                                    },
                                    onOpen = {
                                        submit(
                                            DlnaSetupAction.Browse(
                                                server = currentPhase.server,
                                                folderObjectId = folder.objectId,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Button(
                        enabled = selectedFolders.isNotEmpty(),
                        onClick = {
                            phase = DlnaSetupPhase.Confirm(currentPhase.server)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .highContrastFocus(),
                    ) {
                        Text("Continue (${selectedFolders.size})")
                    }
                }

                is DlnaSetupPhase.Confirm -> {
                    Text(
                        text = "Add ${selectedFolders.size} selected folder(s)?",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    selectedFolders.values.forEach { folder ->
                        Text(folder.title)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .highContrastFocus(RoundedCornerShape(12.dp))
                            .toggleable(
                                value = includeDescendants,
                                role = Role.Switch,
                                onValueChange = { includeDescendants = it },
                            )
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // The row is the focus target; a focusable Switch would steal D-pad focus.
                        Switch(
                            checked = includeDescendants,
                            onCheckedChange = null,
                        )
                        Text("Include subfolders")
                    }
                    Button(
                        onClick = {
                            submit(
                                DlnaSetupAction.Save(
                                    server = currentPhase.server,
                                    folders = selectedFolders.values.map { folder ->
                                        SelectedFolder(
                                            objectId = folder.objectId,
                                            label = folder.title,
                                            includeDescendants = includeDescendants,
                                        )
                                    },
                                ),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .highContrastFocus(),
                    ) {
                        Text("Save")
                    }
                }

                is DlnaSetupPhase.Error -> {
                    Text(
                        text = currentPhase.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        onClick = { submit(currentPhase.retryAction) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .highContrastFocus(),
                    ) {
                        Text("Retry")
                    }
                    TextButton(
                        onClick = { submit(DlnaSetupAction.TryKnown) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .highContrastFocus(),
                    ) {
                        Text("Try known NAS")
                    }
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ProgressMessage(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(message)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun FolderRow(
    folder: BrowseEntry,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .highContrastFocus(RoundedCornerShape(12.dp))
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = onSelectedChange,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The row is the focus target; a focusable Checkbox would steal D-pad focus.
        Checkbox(
            checked = selected,
            onCheckedChange = null,
        )
        Text(
            text = folder.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(
            onClick = onOpen,
            modifier = Modifier.highContrastFocus(),
        ) {
            Text("Open")
        }
    }
}

private sealed interface DlnaSetupPhase {
    data object Idle : DlnaSetupPhase
    data object Discovering : DlnaSetupPhase
    data object LoadingFolders : DlnaSetupPhase
    data object Saving : DlnaSetupPhase

    data class ServerList(
        val servers: List<DiscoveredServer>,
        val statusMessage: String? = null,
    ) : DlnaSetupPhase

    data class Browsing(
        val server: DiscoveredServer,
        val folderObjectId: String,
        val folders: List<BrowseEntry>,
    ) : DlnaSetupPhase

    data class Confirm(
        val server: DiscoveredServer,
    ) : DlnaSetupPhase

    data class Error(
        val message: String,
        val retryAction: DlnaSetupAction,
    ) : DlnaSetupPhase
}

private sealed interface DlnaSetupAction {
    data object Discover : DlnaSetupAction

    data object TryKnown : DlnaSetupAction

    data class Resolve(
        val input: String,
    ) : DlnaSetupAction

    data class Browse(
        val server: DiscoveredServer,
        val folderObjectId: String,
    ) : DlnaSetupAction

    data class Save(
        val server: DiscoveredServer,
        val folders: List<SelectedFolder>,
    ) : DlnaSetupAction
}

internal fun dlnaSetupFailureMessage(
    fallback: String,
    failure: Throwable,
): String {
    val detail =
        when (failure) {
            is SourceFailure -> failure.safeDetail
            else -> failure.message?.takeIf { it.isNotBlank() }?.take(120)
        }
    return if (detail.isNullOrBlank() || detail == fallback) {
        fallback
    } else {
        "$fallback ($detail)"
    }
}
