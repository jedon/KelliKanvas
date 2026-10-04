package com.jedon.kellikanvas.account

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.ui.tv.KanvasButton
import com.jedon.kellikanvas.ui.tv.KanvasColors
import com.jedon.kellikanvas.ui.tv.KanvasNotice
import com.jedon.kellikanvas.ui.tv.KanvasQrCode
import com.jedon.kellikanvas.ui.tv.KanvasSetupScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Suppress("ktlint:standard:function-naming")
@Composable
fun AccountScreen(account: CloudAccount, onChanged: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(account.origin) }
    var email by remember { mutableStateOf(account.accountLabel) }
    var pairing by remember { mutableStateOf<CloudPairing?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var reviewDisconnect by remember { mutableStateOf(false) }
    val syncStatus by account.syncStatus.collectAsState()
    BackHandler(onBack = onBack)
    fun perform(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                work()
                email = account.accountLabel
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "Could not complete account setup. Check the HTTPS server and your connection. If the account changed elsewhere, restore before saving again."
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(pairing) {
        val pair = pairing ?: return@LaunchedEffect
        var approved = false
        try {
            while (System.currentTimeMillis() < pair.expiresAt) {
                delay(pair.interval)
                when (account.poll(pair)) {
                    "approved" -> {
                        approved = true
                        email = account.accountLabel
                        pairing = null
                        message = "TV linked. Choose photos on your phone; this screen receives your saved selections automatically."
                        return@LaunchedEffect
                    }
                    "pending" -> Unit
                    else -> {
                        message = "Pairing expired or was cancelled. Start again for a new code."
                        pairing = null
                        return@LaunchedEffect
                    }
                }
            }
            message = "Pairing code expired. Start again for a new code."
            pairing = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "The server could not be reached. Start again to retry pairing."
            pairing = null
        } finally {
            if (!approved) withContext(NonCancellable) { withTimeoutOrNull(5000) { runCatching { account.cancel(pair) } } }
        }
    }
    KanvasSetupScaffold("Your KelliKanvas account", "Your gallery settings and connections, saved for your screens.", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            message?.let { KanvasNotice(it) }
            if (email == null) {
                if (pairing == null) {
                    Text("Sign in using your phone", style = MaterialTheme.typography.headlineSmall)
                    Text("Scan an HTTPS QR code, sign in with WorkOS, and approve this TV. Your phone can use mobile data or any Wi-Fi network.", color = KanvasColors.Muted)
                    OutlinedTextField(server, { server = it }, label = { Text("Account server") }, singleLine = true, enabled = !busy)
                    KanvasButton(if (busy) "Contacting server…" else "Get phone sign-in code", { perform { pairing = account.start(server, "${Build.MANUFACTURER} ${Build.MODEL}".take(80)) } }, enabled = !busy)
                } else {
                    val pair = checkNotNull(pairing)
                    HostedPhonePrompt(pair.uri, pair.code, server) { pairing = null }
                }
            } else {
                Text("Connected as $email", style = MaterialTheme.typography.titleLarge)
                Text(syncStatus, color = KanvasColors.Muted)
                Text("Choose albums and folders at kanvas.kelli.photo while Kanvas is open on this TV. Phone selections arrive within 30 seconds. Your NAS must be reachable from the TV. The phone's account-only option replaces additional local slideshow selections.", color = KanvasColors.Muted)
                KanvasButton("Restore account to this TV", {
                    perform {
                        message = account.restore()
                        onChanged()
                    }
                }, enabled = !busy)
                KanvasButton("Save this TV to account", { perform { message = account.save() } }, enabled = !busy)
                KanvasButton("Start a new account with this TV's setup", { perform { message = account.initializeFromTv() } }, enabled = !busy)
                if (!reviewDisconnect) {
                    KanvasButton("Disconnect account", { reviewDisconnect = true }, enabled = !busy)
                } else {
                    KanvasNotice("Disconnecting revokes this TV and removes its account-synced credentials and folders. Local photo sources stay on the TV.")
                    KanvasButton("Revoke and remove account from TV", {
                        perform {
                            account.disconnect()
                            reviewDisconnect = false
                            onChanged()
                            message = "Account disconnected"
                        }
                    }, enabled = !busy)
                    KanvasButton("Keep account", { reviewDisconnect = false })
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun HostedPhonePrompt(uri: String, code: String, server: String, onCancel: () -> Unit) {
    Text("Scan with your phone camera", style = MaterialTheme.typography.headlineSmall)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                KanvasQrCode(uri, description = "Account phone sign-in QR code")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HostedPhoneInstructions(code, server, onCancel)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                KanvasQrCode(uri, description = "Account phone sign-in QR code")
                HostedPhoneInstructions(code, server, onCancel)
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun HostedPhoneInstructions(code: String, server: String, onCancel: () -> Unit) {
    Text("TV code: ${code.chunked(4).joinToString(" ")}", style = MaterialTheme.typography.titleLarge)
    Text("Check this code on your phone before approving. This code expires after ten minutes.", color = KanvasColors.Muted)
    Text("Or open $server and enter the TV code after signing in.", color = KanvasColors.Muted)
    KanvasButton("Cancel", onCancel)
}
