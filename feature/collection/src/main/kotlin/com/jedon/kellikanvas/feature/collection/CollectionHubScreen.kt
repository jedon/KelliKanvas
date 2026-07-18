package com.jedon.kellikanvas.feature.collection

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.model.SourceProfileId

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("ktlint:standard:function-naming")
@Composable
fun CollectionHubScreen(
    roots: List<SelectedRoot>,
    sourceLabels: Map<SourceProfileId, String>,
    onAddLocalFolder: () -> Unit,
    onAddQnap: () -> Unit,
    onRemoveRoot: (SelectedRoot) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Collection") },
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
            if (roots.isEmpty()) {
                Text(
                    text = "No photo folders yet. Add a local folder or connect to QNAP.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = roots,
                        key = { root -> "${root.collectionId}:${root.profileId}:${root.objectId}" },
                    ) { root ->
                        RootRow(
                            root = root,
                            sourceLabel = sourceLabels[root.profileId] ?: "Unknown",
                            onRemove = { onRemoveRoot(root) },
                        )
                    }
                }
            }
            Button(
                onClick = onAddLocalFolder,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add local folder")
            }
            Button(
                onClick = onAddQnap,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add QNAP")
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun RootRow(
    root: SelectedRoot,
    sourceLabel: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = root.displayLabel,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = sourceLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove) {
            Text("Remove")
        }
    }
}
