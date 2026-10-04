package com.jedon.kellikanvas.feature.collection

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.jedon.kellikanvas.ui.tv.KanvasButton
import com.jedon.kellikanvas.ui.tv.KanvasColors
import com.jedon.kellikanvas.ui.tv.KanvasNotice
import com.jedon.kellikanvas.ui.tv.KanvasQrCode
import com.jedon.kellikanvas.ui.tv.KanvasSetupScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Base64
import kotlin.coroutines.coroutineContext

@Suppress("ktlint:standard:function-naming")
@Composable
fun SmbSetupScreen(
    controller: SmbSetupController,
    onFinished: (collectionId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isTailscaleConnected: () -> Boolean = { true },
    onOpenTailscale: () -> Unit = {},
    onHostedPhoneSetup: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<SmbSetupPhase>(SmbSetupPhase.Idle) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    fun clearPending() {
        (phase as? SmbSetupPhase.Pairing)?.session?.close()
        (phase as? SmbSetupPhase.Review)?.credentials?.close()
        password = ""
    }
    val close = {
        clearPending()
        onBack()
    }
    BackHandler(onBack = close)
    DisposableEffect(Unit) { onDispose { clearPending() } }

    fun connect(credentials: PhoneNasCredentials) {
        val secret = credentials.password.copyOf()
        credentials.close()
        password = ""
        phase = SmbSetupPhase.Connecting
        scope.launch {
            try {
                phase = SmbSetupPhase.Done(controller.connectHousehold(username = credentials.username, password = secret))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                phase = SmbSetupPhase.Error("Could not connect to DarklingNAS. Check your login and that the NAS is reachable.", !isTailscaleConnected())
            } finally {
                secret.fill('\u0000')
            }
        }.invokeOnCompletion { secret.fill('\u0000') }
    }

    val active = (phase as? SmbSetupPhase.Pairing)?.session
    LaunchedEffect(active) {
        val session = active ?: return@LaunchedEffect
        try {
            val credentials = withTimeout((session.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(1)) { session.awaitCredentials() }
            phase = SmbSetupPhase.Review(credentials)
        } catch (_: TimeoutCancellationException) {
            phase = SmbSetupPhase.Expired
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            coroutineContext.ensureActive()
            phase = if (System.currentTimeMillis() >= session.expiresAtMillis) SmbSetupPhase.Expired else SmbSetupPhase.Error("Phone pairing stopped. Start again to get a new QR code.")
        } finally {
            session.close()
        }
    }

    KanvasSetupScaffold("Connect your NAS", "Bring your household photo library to your TV.", close, modifier) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (val current = phase) {
                SmbSetupPhase.Idle -> {
                    Text("Connect DarklingNAS", style = MaterialTheme.typography.titleLarge)
                    onHostedPhoneSetup?.let { open ->
                        KanvasButton("Set up securely through your account", open, primary = true)
                        Text("Use the hosted HTTPS phone sign-in to save your NAS details for your screens.", color = KanvasColors.Muted)
                    }
                    Text("Use your phone's keyboard to enter your NAS login. Your password is saved securely on this TV after connecting.", color = KanvasColors.Muted)
                    KanvasButton("Enter login using your phone", {
                        phase = SmbSetupPhase.Starting
                        scope.launch {
                            var opened: PhoneNasPairingSession? = null
                            try {
                                withContext(Dispatchers.IO) { opened = startPhoneNasPairing(context) }
                                phase = SmbSetupPhase.Pairing(requireNotNull(opened))
                                opened = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                phase = SmbSetupPhase.Error("Phone pairing needs this TV connected to your home Wi-Fi or Ethernet. Allow local network access, or enter the login on this device.")
                            } finally {
                                opened?.close()
                            }
                        }
                    }, primary = true)
                    KanvasButton("Enter on this device", { phase = SmbSetupPhase.Manual })
                    Text("Away from home, Tailscale can reach the NAS. Pair your phone while it's on the same trusted Wi-Fi as this TV.", color = KanvasColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                SmbSetupPhase.Starting -> {
                    CircularProgressIndicator(color = KanvasColors.Accent)
                    Text("Preparing phone setup…")
                }
                is SmbSetupPhase.Pairing -> NasPhonePairingPrompt(current.session.url, current.session.expiresAtMillis) {
                    current.session.close()
                    phase = SmbSetupPhase.Idle
                }
                is SmbSetupPhase.Review -> NasPhoneReviewPrompt(current.credentials.username, { connect(current.credentials) }) {
                    current.credentials.close()
                    phase = SmbSetupPhase.Idle
                }
                SmbSetupPhase.Manual -> {
                    OutlinedTextField(username, { username = it.take(128) }, label = { Text("NAS username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it.take(256) }, label = { Text("NAS password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    KanvasButton("Connect NAS", { connect(PhoneNasCredentials(username.trim(), password.toCharArray())) }, enabled = username.isNotBlank() && password.isNotEmpty(), primary = true)
                    KanvasButton("Use my phone instead", {
                        password = ""
                        phase = SmbSetupPhase.Idle
                    })
                }
                SmbSetupPhase.Connecting -> {
                    CircularProgressIndicator(color = KanvasColors.Accent)
                    Text("Connecting to DarklingNAS…")
                }
                is SmbSetupPhase.Done -> {
                    Text("Your NAS is connected.", style = MaterialTheme.typography.titleLarge)
                    Text("Added ${current.result.rootCount} photo folder(s).", color = KanvasColors.Muted)
                    current.result.roots.forEach { Text(it) }
                    KanvasButton("Done", { onFinished(current.result.collectionId) }, primary = true)
                }
                SmbSetupPhase.Expired -> {
                    KanvasNotice("This pairing link expired. Start again to get a new QR code.")
                    KanvasButton("Start again", { phase = SmbSetupPhase.Idle }, primary = true)
                }
                is SmbSetupPhase.Error -> {
                    KanvasNotice(current.message, error = true)
                    if (current.tailscaleDisconnected) {
                        Text("Tailscale isn't connected. Open it to reach DarklingNAS away from home.", color = KanvasColors.Muted)
                        KanvasButton("Open Tailscale", onOpenTailscale)
                    }
                    KanvasButton("Try again", { phase = SmbSetupPhase.Idle }, primary = true)
                    KanvasButton("Enter on this device", { phase = SmbSetupPhase.Manual })
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun NasPhonePairingPrompt(url: String, expiresAtMillis: Long, onCancel: () -> Unit) {
    var seconds by remember(url) { mutableStateOf(((expiresAtMillis - System.currentTimeMillis()) / 1_000).coerceAtLeast(0)) }
    LaunchedEffect(url) {
        while (seconds > 0) {
            delay(1_000)
            seconds = ((expiresAtMillis - System.currentTimeMillis()) / 1_000).coerceAtLeast(0)
        }
    }
    Text("Enter your NAS login on your phone", style = MaterialTheme.typography.titleLarge)
    Text("Scan with your phone camera. Enter your login, then select Connect NAS here on the TV.", color = KanvasColors.Muted)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val details: @Composable () -> Unit = {
            Text("Use the same trusted home Wi-Fi as this TV.", style = MaterialTheme.typography.titleMedium)
            Text("This temporary local page uses HTTP. The link expires in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} and closes after one submission.", color = KanvasColors.Muted)
            Text("Waiting for your phone…", color = KanvasColors.Muted)
            KanvasButton("Cancel phone setup", onCancel)
        }
        if (maxWidth >= 600.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                KanvasQrCode(url, "Scan to enter your NAS login on your phone")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { details() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                KanvasQrCode(url, "Scan to enter your NAS login on your phone")
                details()
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun NasPhoneReviewPrompt(username: String, onConnect: () -> Unit, onDiscard: () -> Unit) {
    Text("Login received from your phone", style = MaterialTheme.typography.titleLarge)
    Text("Connect to DarklingNAS as $username.", color = KanvasColors.Muted)
    Text("Your password stays hidden and is stored securely after the connection succeeds.", color = KanvasColors.Muted)
    KanvasButton("Connect NAS", onConnect, primary = true)
    KanvasButton("Discard login", onDiscard)
}

// Include physical LAN networks while Tailscale is the active VPN.
@Suppress("DEPRECATION")
fun startPhoneNasPairing(context: Context, options: PhonePairingOptions = PhonePairingOptions()): PhoneNasPairingSession {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val networkAddresses = connectivity.allNetworks.toList().flatMap { network ->
        val capabilities = connectivity.getNetworkCapabilities(network)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
            connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }
        } else {
            emptyList()
        }
    }
    val address = (networkAddresses + NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() })
        .filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        ?: error("No local network address")
    val bitmap = android.graphics.BitmapFactory.decodeResource(context.resources, com.jedon.kellikanvas.ui.tv.R.drawable.kanvas_logo)
    val logo = java.io.ByteArrayOutputStream().use { output ->
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        Base64.getEncoder().encodeToString(output.toByteArray())
    }
    return PhoneNasPairingSession.start(address, logo, options)
}

private sealed interface SmbSetupPhase {
    data object Idle : SmbSetupPhase
    data object Starting : SmbSetupPhase
    data object Manual : SmbSetupPhase
    data object Connecting : SmbSetupPhase
    data object Expired : SmbSetupPhase
    data class Pairing(val session: PhoneNasPairingSession) : SmbSetupPhase
    data class Review(val credentials: PhoneNasCredentials) : SmbSetupPhase
    data class Done(val result: HouseholdConnectResult) : SmbSetupPhase
    data class Error(val message: String, val tailscaleDisconnected: Boolean = false) : SmbSetupPhase
}
