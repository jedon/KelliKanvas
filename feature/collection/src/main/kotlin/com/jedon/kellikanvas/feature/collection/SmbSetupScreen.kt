package com.jedon.kellikanvas.feature.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.source.smb.HouseholdNasDefaults
import com.jedon.kellikanvas.ui.tv.highContrastFocus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("ktlint:standard:function-naming")
@Composable
fun SmbSetupScreen(
    controller: SmbSetupController,
    onFinished: (collectionId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<SmbSetupPhase>(SmbSetupPhase.Idle) }

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Household NAS (SMB)") },
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
            modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val current = phase) {
                SmbSetupPhase.Idle -> {
                    Text(
                        text =
                        "Connect to ${HouseholdNasDefaults.DISPLAY_NAME} " +
                            "(${HouseholdNasDefaults.PRIMARY_HOST}) over SMB and add photo folders.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Primary share: ${HouseholdNasDefaults.PRIMARY_SHARE.share}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                phase = SmbSetupPhase.Connecting
                                runCatching { controller.connectHousehold() }
                                    .onSuccess { result ->
                                        phase = SmbSetupPhase.Done(result)
                                    }
                                    .onFailure {
                                        phase =
                                            SmbSetupPhase.Error(
                                                "Could not connect to household NAS over SMB.",
                                            )
                                    }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .highContrastFocus(),
                    ) {
                        Text("Connect household NAS")
                    }
                }

                SmbSetupPhase.Connecting -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Connecting to household NAS…")
                    }
                }

                is SmbSetupPhase.Done -> {
                    Text(
                        text =
                        "Connected to \\\\${current.result.host}\\${current.result.share}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Added ${current.result.rootCount} photo folder(s):",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(current.result.roots) { root ->
                            Text(text = root, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Button(
                        onClick = { onFinished(current.result.collectionId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .highContrastFocus(),
                    ) {
                        Text("Done")
                    }
                }

                is SmbSetupPhase.Error -> {
                    Text(
                        text = current.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { phase = SmbSetupPhase.Idle },
                        modifier = Modifier
                            .fillMaxWidth()
                            .highContrastFocus(),
                    ) {
                        Text("Try again")
                    }
                }
            }
        }
    }
}

private sealed interface SmbSetupPhase {
    data object Idle : SmbSetupPhase

    data object Connecting : SmbSetupPhase

    data class Done(
        val result: HouseholdConnectResult,
    ) : SmbSetupPhase

    data class Error(
        val message: String,
    ) : SmbSetupPhase
}
