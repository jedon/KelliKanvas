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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
    onConnectHouseholdNas: () -> Unit,
    onRemoveRoot: (SelectedRoot) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backHandlerEnabled: Boolean = true,
    loadError: String? = null,
) {
    BackHandler(enabled = backHandlerEnabled, onBack = onBack)

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Collection") },
                windowInsets = WindowInsets.statusBars,
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
        // Prefer verticalScroll over LazyColumn: HorizontalPager pages can measure with
        // infinite max height, which crashes nested LazyColumn ("infinity maximum height").
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (loadError != null) {
                Text(
                    text = loadError,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (roots.isEmpty()) {
                Text(
                    text = "No photo folders yet. Add a local folder, household NAS, or QNAP DLNA.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                roots.forEach { root ->
                    RootRow(
                        root = root,
                        sourceLabel = sourceLabels[root.profileId] ?: "Unknown",
                        onRemove = { onRemoveRoot(root) },
                    )
                }
            }
            HighContrastFocusButton(
                onClick = onAddLocalFolder,
                label = "Add local folder",
                modifier = Modifier.fillMaxWidth(),
            )
            HighContrastFocusButton(
                onClick = onConnectHouseholdNas,
                label = "Connect household NAS",
                modifier = Modifier.fillMaxWidth(),
            )
            HighContrastFocusButton(
                onClick = onAddQnap,
                label = "Add QNAP (DLNA)",
                modifier = Modifier.fillMaxWidth(),
            )
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
        HighContrastFocusButton(
            onClick = onRemove,
            label = "Remove",
            minHeightDp = 48,
        )
    }
}
