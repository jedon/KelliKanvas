package com.jedon.kellikanvas.feature.setup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.saf.SafProfile
import com.jedon.kellikanvas.source.saf.SafTreeGrant
import com.jedon.kellikanvas.source.saf.SafTreePickerContract
import kotlinx.coroutines.launch
import java.util.UUID

@Suppress("ktlint:standard:function-naming")
@Composable
fun SafSetupScreen(
    controller: SafSetupController,
    onFinished: (collectionId: String) -> Unit,
    onOpenMenu: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var grant by remember { mutableStateOf<SafTreeGrant?>(null) }
    var displayName by remember { mutableStateOf<String?>(null) }
    var includeDescendants by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = SafTreePickerContract(context.contentResolver),
    ) { selectedGrant ->
        if (selectedGrant == null) {
            errorMessage = "No folder selected"
            return@rememberLauncherForActivityResult
        }
        grant = selectedGrant
        displayName = selectedGrant.treeUri.displayName()
        errorMessage = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
            .widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Choose a photos folder",
            style = MaterialTheme.typography.headlineMedium,
        )
        Button(onClick = {
            errorMessage = null
            picker.launch(null)
        }) {
            Text(text = displayName ?: "Choose folder")
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Switch(
                checked = includeDescendants,
                onCheckedChange = { includeDescendants = it },
            )
            Text(text = "Include subfolders")
        }
        Button(
            enabled = grant != null && displayName != null,
            onClick = {
                val selectedGrant = grant ?: return@Button
                val selectedName = displayName ?: return@Button
                scope.launch {
                    runCatching {
                        controller.complete(
                            profile = SafProfile(
                                id = SourceProfileId("saf-${UUID.randomUUID()}"),
                                grant = selectedGrant,
                            ),
                            displayName = selectedName,
                            includeDescendants = includeDescendants,
                        )
                    }.onSuccess(onFinished)
                        .onFailure {
                            errorMessage = "Could not save folder selection"
                        }
                }
            },
        ) {
            Text(text = "Confirm")
        }
        if (onOpenMenu != null) {
            TextButton(onClick = onOpenMenu) {
                Text(text = "Open menu")
            }
        }
        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun Uri.displayName(): String {
    val segment = lastPathSegment ?: return "Photos"
    val decoded = Uri.decode(segment)
    return decoded.substringAfterLast(':').takeIf { it.isNotBlank() }
        ?: decoded.takeIf { it.isNotBlank() }
        ?: "Photos"
}
