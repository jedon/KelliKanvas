package com.jedon.kellikanvas.connectors

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.AppContainer
import com.jedon.kellikanvas.feature.collection.PhoneNasPairingSession
import com.jedon.kellikanvas.feature.collection.PhonePairingOptions
import com.jedon.kellikanvas.feature.collection.startPhoneNasPairing
import com.jedon.kellikanvas.model.Page
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.connected.ConnectedPhotoSource
import com.jedon.kellikanvas.source.connected.ConnectorConfiguration
import com.jedon.kellikanvas.source.connected.PhotoConnector
import com.jedon.kellikanvas.source.connected.connectedPhotoSource
import com.jedon.kellikanvas.ui.tv.KanvasButton
import com.jedon.kellikanvas.ui.tv.KanvasColors
import com.jedon.kellikanvas.ui.tv.KanvasNotice
import com.jedon.kellikanvas.ui.tv.KanvasQrCode
import com.jedon.kellikanvas.ui.tv.KanvasSetupScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

@Suppress("ktlint:standard:function-naming")
@Composable
fun ConnectorSetupScreen(container: AppContainer, provider: PhotoConnector, existingId: SourceProfileId?, onFinished: () -> Unit, onBack: () -> Unit, onHostedPhoneSetup: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = remember(existingId) { existingId ?: SourceProfileId(UUID.randomUUID().toString()) }
    var endpoint by remember { mutableStateOf(provider.fixedEndpoint) }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<PhoneNasPairingSession?>(null) }
    var config by remember { mutableStateOf<ConnectorConfiguration?>(null) }
    var adapter by remember { mutableStateOf<ConnectedPhotoSource?>(null) }
    var folders by remember { mutableStateOf<List<SourceEntry.Folder>>(emptyList()) }
    var listing by remember { mutableStateOf<Page<SourceEntry>?>(null) }
    var descendants by remember { mutableStateOf(true) }
    val controller = remember(container) { ConnectorController(container.database, container.connectorStore) }
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = when (failure) {
                    is SourceFailure -> failure.safeDetail
                    is IllegalArgumentException -> failure.message ?: "Check your connection details"
                    else -> "Could not connect. Check your server address, login and network."
                }
            } finally {
                busy = false
            }
        }
    }
    DisposableEffect(session) {
        val current = session
        onDispose { current?.close() }
    }
    LaunchedEffect(existingId) {
        if (existingId != null) {
            container.connectorStore.read(id)?.let {
                endpoint = it.endpoint.toString()
                username = it.username
            }
        }
    }
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        try {
            withTimeout((current.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(1)) {
                current.awaitCredentials().use { credentials ->
                    endpoint = provider.fixedEndpoint.ifEmpty { credentials.endpoint }
                    username = credentials.username
                    secret = String(credentials.password)
                }
            }
            session = null
        } catch (cancelled: kotlinx.coroutines.TimeoutCancellationException) {
            error = "The phone link expired. Start again."
            session = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Phone setup closed. Start again for a new link."
            session = null
        } finally {
            current.close()
        }
    }
    val close: () -> Unit = {
        session?.close()
        secret = ""
        onBack()
    }
    BackHandler(onBack = close)
    KanvasSetupScaffold("Connect ${provider.title}", provider.help, close) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            error?.let { KanvasNotice(it, error = true) }
            if (busy) CircularProgressIndicator(color = KanvasColors.Accent)
            val phone = session
            val source = adapter
            val folder = folders.lastOrNull()
            when {
                phone != null -> {
                    Text("Continue on your phone", style = MaterialTheme.typography.titleLarge)
                    KanvasQrCode(phone.url, "Scan to set up ${provider.title} on your phone")
                    Text("Use the same trusted home Wi-Fi as your TV. This temporary HTTP link expires in 10 minutes. Confirm here after sending your login.", color = KanvasColors.Muted)
                    KanvasButton("Cancel phone setup", { session = null })
                }
                source != null && folder != null -> {
                    Text(folder.name, style = MaterialTheme.typography.titleLarge)
                    Text("${listing?.items?.count { it is SourceEntry.Photo } ?: 0} photos on this page", color = KanvasColors.Muted)
                    if (folders.size > 1) {
                        KanvasButton("Parent folder", {
                            perform {
                                val parent = folders.dropLast(1)
                                listing = source.listChildren(parent.last().ref, null)
                                folders = parent
                            }
                        }, enabled = !busy)
                    }
                    listing?.items?.filterIsInstance<SourceEntry.Folder>()?.forEach { child ->
                        KanvasButton("Open ${child.name}", {
                            perform {
                                listing = source.listChildren(child.ref, null)
                                folders = folders + child
                            }
                        }, enabled = !busy)
                    }
                    listing?.nextCursor?.let { cursor -> KanvasButton("Next page", { perform { listing = source.listChildren(folder.ref, cursor) } }, enabled = !busy) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Include subfolders")
                        Switch(descendants, { descendants = it }, enabled = !busy)
                    }
                    KanvasButton("Add this photo folder", {
                        perform {
                            controller.select(id, checkNotNull(config), folder, descendants)
                            secret = ""
                            onFinished()
                        }
                    }, enabled = !busy, primary = true)
                    KanvasButton("Change connection", {
                        adapter = null
                        config = null
                        listing = null
                        secret = ""
                    }, enabled = !busy)
                }
                else -> {
                    onHostedPhoneSetup?.let { open -> KanvasButton("Set up securely through your account", open, enabled = !busy, primary = true) }
                    KanvasButton("Set up using your phone", {
                        perform {
                            var opened: PhoneNasPairingSession? = null
                            try {
                                val next = withContext(Dispatchers.IO) {
                                    startPhoneNasPairing(context, PhonePairingOptions(provider.title, provider.help, true, provider.passwordLogin, secretLabel = provider.secretLabel, usernameLabel = provider.usernameLabel, fixedEndpoint = provider.fixedEndpoint)).also { opened = it }
                                }
                                session = next
                                opened = null
                            } finally {
                                opened?.close()
                            }
                        }
                    }, enabled = !busy)
                    Text("Or enter the details on this device", color = KanvasColors.Muted)
                    if (provider.fixedEndpoint.isEmpty()) OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Server or WebDAV URL") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    if (provider.passwordLogin) OutlinedTextField(username, { username = it }, label = { Text(provider.usernameLabel) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(secret, { secret = it }, label = { Text(provider.secretLabel) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    KanvasButton("Connect ${provider.title}", {
                        perform {
                            val connection = ConnectorConfiguration(provider, endpoint, username.trim(), secret)
                            val next = connectedPhotoSource(id, connection, container.httpClient)
                            val root = SourceEntry.Folder(next.rootFolder, provider.title)
                            val first = next.listChildren(root.ref, null)
                            config = connection
                            adapter = next
                            folders = listOf(root)
                            listing = first
                            secret = ""
                        }
                    }, enabled = !busy && secret.isNotBlank(), primary = true)
                }
            }
        }
    }
}
