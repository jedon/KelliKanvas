package com.jedon.kellikanvas.feature.setup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.saf.SafProfile
import com.jedon.kellikanvas.source.saf.SafTreeGrant
import com.jedon.kellikanvas.source.saf.SafTreePickerContract
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun SafSetupScreen(
    controller: SafSetupController,
    onFinished: (collectionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var grant by remember { mutableStateOf<SafTreeGrant?>(null) }
    var displayName by remember { mutableStateOf<String?>(null) }
    var includeDescendants by remember { mutableStateOf(true) }

    val picker = rememberLauncherForActivityResult(
        contract = SafTreePickerContract(context.contentResolver),
    ) { selectedGrant ->
        grant = selectedGrant
        displayName = selectedGrant?.treeUri?.displayName()
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
            style = MaterialTheme.typography.displaySmall,
        )
        Button(onClick = { picker.launch(null) }) {
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
                    val collectionId = controller.complete(
                        profile = SafProfile(
                            id = SourceProfileId("saf-${UUID.randomUUID()}"),
                            grant = selectedGrant,
                        ),
                        displayName = selectedName,
                        includeDescendants = includeDescendants,
                    )
                    onFinished(collectionId)
                }
            },
        ) {
            Text(text = "Confirm")
        }
    }
}

private fun Uri.displayName(): String? =
    lastPathSegment
        ?.substringAfterLast(':')
        ?.takeIf { it.isNotBlank() }
