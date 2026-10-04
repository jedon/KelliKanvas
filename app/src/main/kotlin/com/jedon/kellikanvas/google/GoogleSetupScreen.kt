package com.jedon.kellikanvas.google

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.jedon.kellikanvas.catalog.GoogleConnection
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.SourceProfileKind
import com.jedon.kellikanvas.model.Page
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.google.GoogleAmbientDevice
import com.jedon.kellikanvas.source.google.GoogleDeviceChallenge
import com.jedon.kellikanvas.source.google.GoogleDriveSourceAdapter
import com.jedon.kellikanvas.source.google.GooglePhotosSourceAdapter
import com.jedon.kellikanvas.ui.tv.KanvasButton
import com.jedon.kellikanvas.ui.tv.KanvasColors
import com.jedon.kellikanvas.ui.tv.KanvasNotice
import com.jedon.kellikanvas.ui.tv.KanvasQrCode
import com.jedon.kellikanvas.ui.tv.KanvasSetupScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** Authorization stays outside the app's UI: native Google consent or a phone pairing code. */
@Suppress("ktlint:standard:function-naming")
@Composable
fun GoogleSetupScreen(
    sources: GoogleSources,
    database: KelliKanvasDatabase,
    kind: SourceKind,
    existingId: SourceProfileId?,
    onFinished: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(database) { GoogleSourceController(database) }
    var id by remember(existingId) { mutableStateOf(existingId ?: SourceProfileId(UUID.randomUUID().toString())) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var drive by remember { mutableStateOf<GoogleDriveSourceAdapter?>(null) }
    var folders by remember { mutableStateOf<List<SourceEntry.Folder>>(emptyList()) }
    var page by remember { mutableStateOf<Page<SourceEntry>?>(null) }
    var includeSubfolders by remember { mutableStateOf(true) }
    var folderLocation by remember { mutableStateOf("") }
    var challenge by remember { mutableStateOf<GoogleDeviceChallenge?>(null) }
    var device by remember { mutableStateOf<GoogleAmbientDevice?>(null) }
    var connection by remember { mutableStateOf<GoogleConnection?>(null) }

    fun perform(block: suspend () -> Unit) {
        operation = scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = googleSetupError(failure)
            } finally {
                busy = false
            }
        }
    }

    suspend fun acceptDrive(result: AuthorizationResult) {
        val adapter = sources.acceptDrive(id, result)
        val folder = adapter.folder("root")
        val listing = adapter.listChildren(folder.ref, null)
        drive = adapter
        folders = listOf(folder)
        page = listing
    }

    val authorize = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            busy = false
            error = "Google sign-in was cancelled. You can try again."
        } else {
            perform { acceptDrive(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data)) }
        }
    }

    val close: () -> Unit = {
        operation?.cancel()
        perform {
            val pending = database.googleConnections.get(id)
            if (controller.removeUnused(id)) {
                try {
                    if (pending != null) sources.disconnect(pending) else sources.grants.remove(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    sources.grants.remove(id)
                }
            }
            onBack()
        }
    }
    BackHandler(onBack = close)

    LaunchedEffect(existingId, kind) {
        // Resume an unfinished pairing after an app restart, avoiding another ambient device.
        val saved = if (existingId != null) {
            database.googleConnections.get(existingId)
        } else {
            database.googleConnections.list().firstOrNull { candidate ->
                database.sourceProfiles.get(candidate.profileId)?.kind == SourceProfileKind.Known(kind) &&
                    database.collections.list().none { collection -> database.selectedRoots.list(collection.id).any { it.profileId == candidate.profileId } }
            }
        }
        if (saved != null) {
            id = saved.profileId
            connection = saved
            if (kind == SourceKind.GOOGLE_PHOTOS && sources.grants.read(id) != null) {
                perform { device = (sources.restore(saved, kind) as? GooglePhotosSourceAdapter)?.device() }
            }
        }
    }

    LaunchedEffect(device?.id) {
        val initial = device ?: return@LaunchedEffect
        if (initial.sourcesSet) return@LaunchedEffect
        try {
            val adapter = GooglePhotosSourceAdapter(id, initial.id, sources.photosHttp(id))
            while (device?.sourcesSet == false) {
                delay((device?.pollSeconds ?: 5) * 1_000)
                device = adapter.device()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = googleSetupError(failure)
        }
    }

    KanvasSetupScaffold(
        title = if (kind == SourceKind.GOOGLE_DRIVE) "Connect Google Drive" else "Connect Google Photos",
        subtitle = if (kind == SourceKind.GOOGLE_DRIVE) "Choose a folder from your Google account." else "Choose the albums to display on your TV, from your phone.",
        onBack = close,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            error?.let { KanvasNotice(it, error = true) }
            if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = KanvasColors.Accent)
            val activeDrive = drive
            val activeDevice = device
            val activeChallenge = challenge
            when {
                activeDrive != null -> {
                    Text(folders.joinToString(" / ") { it.name }, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        KanvasButton("Add this folder", {
                            perform {
                                val folder = folders.last()
                                controller.select(GoogleConnection(id), kind, folder.ref, folder.name, includeSubfolders)
                                onFinished()
                            }
                        }, enabled = !busy, primary = true)
                        if (folders.size > 1) {
                            KanvasButton("Up a folder", {
                                perform {
                                    val parent = folders[folders.lastIndex - 1]
                                    page = activeDrive.listChildren(parent.ref, null)
                                    folders = folders.dropLast(1)
                                }
                            }, enabled = !busy)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Include subfolders", Modifier.weight(1f))
                        Switch(includeSubfolders, { includeSubfolders = it }, enabled = !busy)
                    }
                    val listing = page
                    Text("${listing?.items?.count { it is SourceEntry.Photo } ?: 0} photos on this page", color = KanvasColors.Muted)
                    listing?.items?.filterIsInstance<SourceEntry.Folder>()?.forEach { folder ->
                        KanvasButton(folder.name, {
                            perform {
                                page = activeDrive.listChildren(folder.ref, null)
                                folders = folders + folder
                            }
                        }, Modifier.fillMaxWidth(), enabled = !busy)
                    }
                    listing?.nextCursor?.let { next ->
                        KanvasButton("Next page", { perform { page = activeDrive.listChildren(folders.last().ref, next) } }, enabled = !busy)
                    }
                    OutlinedTextField(folderLocation, { folderLocation = it }, label = { Text("Drive folder link or ID") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    KanvasButton("Open folder link", {
                        perform {
                            val folder = activeDrive.folder(parseDriveFolderLocation(folderLocation))
                            page = activeDrive.listChildren(folder.ref, null)
                            folders = listOf(activeDrive.folder("root"), folder)
                        }
                    }, enabled = !busy && folderLocation.isNotBlank())
                }
                activeDevice != null -> {
                    GoogleAlbumsPrompt(activeDevice, busy) {
                        perform {
                            val adapter = GooglePhotosSourceAdapter(id, activeDevice.id, sources.photosHttp(id))
                            if (!adapter.device().sourcesSet) throw SourceFailure.SourceUnavailable(id, "connect", "Choose albums in Google Photos first")
                            controller.select(connection!!, kind, adapter.rootFolder, "Google Photos albums", false)
                            onFinished()
                        }
                    }
                }
                activeChallenge != null -> {
                    GooglePairingPrompt(activeChallenge, busy) {
                        operation?.cancel()
                        challenge = null
                        error = null
                    }
                }
                else -> {
                    Text("Your photos stay in your Google account. KelliKanvas accesses them to show your gallery and screensaver. You can remove the connection from Collection at any time.", color = KanvasColors.Muted)
                    if (kind == SourceKind.GOOGLE_PHOTOS && !sources.photosConfiguration.configured) {
                        KanvasNotice("Google Photos has not been enabled in this build. The app's Google connection needs to be configured before sign-in.")
                    }
                    KanvasButton(if (kind == SourceKind.GOOGLE_PHOTOS) "Sign in using your phone" else "Continue with Google", {
                        perform {
                            if (kind == SourceKind.GOOGLE_DRIVE) {
                                val result = sources.driveAuthorization(id).awaitGoogleTask()
                                if (result.hasResolution()) {
                                    authorize.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
                                } else {
                                    acceptDrive(result)
                                }
                            } else {
                                val oauth = sources.photosOAuth(id)
                                val code = oauth.begin()
                                challenge = code
                                sources.grants.write(id, oauth.awaitGrant(code))
                                challenge = null
                                val saved = database.googleConnections.get(id)
                                val http = sources.photosHttp(id)
                                // Match OAuth state so the phone proceeds straight to album selection.
                                val registered = GooglePhotosSourceAdapter.createDevice(http, requireNotNull(code.requestId))
                                val metadata = GoogleConnection(id, registered.id, registered.settingsUri, sources.photosConfiguration.clientId)
                                controller.saveConnection(metadata, kind)
                                connection = metadata
                                device = registered
                                if (saved != null && saved.resourceId != registered.id && saved.clientId == sources.photosConfiguration.clientId) {
                                    try {
                                        GooglePhotosSourceAdapter(id, saved.resourceId, http).deleteDevice()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: SourceFailure) {
                                        // A previous account may no longer allow access to its old device.
                                    }
                                }
                            }
                        }
                    }, enabled = !busy && (kind == SourceKind.GOOGLE_DRIVE || sources.photosConfiguration.configured), primary = true)
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun GooglePairingPrompt(challenge: GoogleDeviceChallenge, busy: Boolean, onRestart: () -> Unit) {
    var secondsLeft by remember(challenge) { mutableStateOf(((challenge.expiresAtMillis - System.currentTimeMillis()) / 1_000).coerceAtLeast(0)) }
    LaunchedEffect(challenge) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft = ((challenge.expiresAtMillis - System.currentTimeMillis()) / 1_000).coerceAtLeast(0)
        }
    }
    if (secondsLeft == 0L) {
        Text("Sign-in code expired", style = MaterialTheme.typography.titleLarge)
        Text("Start again to get a new QR code, then finish signing in on your phone.", color = KanvasColors.Muted)
        KanvasButton("Start again", onRestart)
        return
    }
    GoogleQrPanel(challenge.signInUrl, "Sign in using your phone", "Scan with your phone camera. Sign in to Google, then choose the albums for this display.") {
        Text("Enter this code: ${challenge.userCode}", style = MaterialTheme.typography.headlineMedium)
        Text("If you can't scan, open ${challenge.verificationUrl} on your phone and enter the code above.", color = KanvasColors.Muted)
        Text("Code expires in ${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.bodySmall, color = KanvasColors.Muted)
        Text(if (busy) "Waiting for you to finish on your phone…" else "Sign-in paused. Start again to reconnect.", color = KanvasColors.Muted)
        KanvasButton("Start again", onRestart)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun GoogleAlbumsPrompt(device: GoogleAmbientDevice, busy: Boolean, onAdd: () -> Unit) {
    GoogleQrPanel(device.settingsUri, if (device.sourcesSet) "Your albums are ready." else "Choose your albums in Google Photos.", "Continue on your phone to choose albums. If the selection page didn't open, scan this code.") {
        KanvasButton("Add Google Photos", onAdd, enabled = device.sourcesSet && !busy, primary = true)
        Text("Albums update automatically. Google selects a rotating set of up to 100 photos for this display.", color = KanvasColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun GoogleQrPanel(url: String, title: String, description: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, color = KanvasColors.Muted)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 600.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    GoogleLinkCode(url)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    GoogleLinkCode(url)
                    content()
                }
            }
        }
    }
}

private fun googleSetupError(failure: Exception): String = when (failure) {
    is SourceFailure -> failure.safeDetail
    else -> "Google could not connect. Check your internet connection and the app's Google sign-in configuration, then try again."
}

internal fun parseDriveFolderLocation(value: String): String {
    val input = value.trim()
    if (input.matches(Regex("[A-Za-z0-9_-]{1,256}"))) return input
    val url = input.toHttpUrlOrNull()
    require(url != null && url.isHttps && url.host == "drive.google.com") { "Use a Google Drive folder link" }
    val index = url.pathSegments.indexOf("folders")
    val id = if (index >= 0) url.pathSegments.getOrNull(index + 1) else url.queryParameter("id")
    require(id != null && id.matches(Regex("[A-Za-z0-9_-]{1,256}"))) { "Use a Google Drive folder link" }
    return id
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun GoogleLinkCode(url: String) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        KanvasQrCode(url, "Scan to open Google on your phone")
        KanvasButton("Open Google on this device", {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        })
    }
}
