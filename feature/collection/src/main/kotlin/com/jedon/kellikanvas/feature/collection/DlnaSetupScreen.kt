package com.jedon.kellikanvas.feature.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
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
                        .onSuccess { phase = DlnaSetupPhase.ServerList(it) }
                        .onFailure {
                            phase = DlnaSetupPhase.Error(
                                message = "Could not discover QNAP servers.",
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
                        phase = DlnaSetupPhase.Browsing(
                            server = action.server,
                            folderObjectId = action.folderObjectId,
                            folders = folders,
                        )
                    }.onFailure {
                        phase = DlnaSetupPhase.Error(
                            message = "Could not load folders from this server.",
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
        topBar = {
            TopAppBar(
                title = { Text("Add QNAP") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Discover servers")
                    }
                }

                DlnaSetupPhase.Discovering -> ProgressMessage("Discovering servers…")
                DlnaSetupPhase.LoadingFolders -> ProgressMessage("Loading folders…")
                DlnaSetupPhase.Saving -> ProgressMessage("Saving selection…")

                is DlnaSetupPhase.ServerList -> {
                    Text(
                        text = if (currentPhase.servers.isEmpty()) {
                            "No servers found. Enter a host name or IP address."
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
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(server.friendlyName)
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
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Connect")
                    }
                    TextButton(
                        onClick = { submit(DlnaSetupAction.Discover) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Discover again")
                    }
                }

                is DlnaSetupPhase.Browsing -> {
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
                        modifier = Modifier.fillMaxWidth(),
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
                            .clickable { includeDescendants = !includeDescendants },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Switch(
                            checked = includeDescendants,
                            onCheckedChange = { includeDescendants = it },
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
                        modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Retry")
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
            .clickable { onSelectedChange(!selected) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = onSelectedChange,
        )
        Text(
            text = folder.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = onOpen) {
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
