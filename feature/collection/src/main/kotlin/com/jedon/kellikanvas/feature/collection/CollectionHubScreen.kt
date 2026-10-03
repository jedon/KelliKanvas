package com.jedon.kellikanvas.feature.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.ui.tv.KanvasButton
import com.jedon.kellikanvas.ui.tv.KanvasColors
import com.jedon.kellikanvas.ui.tv.KanvasNotice
import com.jedon.kellikanvas.ui.tv.KanvasPageHeader

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
    var pendingRemoval by remember { mutableStateOf<SelectedRoot?>(null) }
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 600.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (wide) 32.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                KanvasPageHeader("Your collection", "The photo folders that make your gallery.", Modifier.weight(1f), "LIBRARY")
                KanvasButton("Back", onBack)
            }
            loadError?.let { KanvasNotice(it, error = true) }
            Text("PHOTO FOLDERS · ${roots.size}", style = MaterialTheme.typography.labelMedium, color = KanvasColors.Muted)
            if (roots.isEmpty()) {
                Surface(color = KanvasColors.Surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.AutoMirrored.Filled.List, null, tint = KanvasColors.Accent)
                        Text("Good memories deserve a bigger screen.", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Choose a source below. Your photos stay in their original folder.",
                            color = KanvasColors.Muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                roots.forEach { root ->
                    Surface(
                        color = KanvasColors.Surface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, KanvasColors.Border),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.List, null, tint = KanvasColors.Accent)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(root.displayLabel, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    sourceLabels[root.profileId] ?: "Photo source",
                                    color = KanvasColors.Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (root.includeDescendants) "Includes subfolders" else "This folder only",
                                    color = KanvasColors.Muted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            KanvasButton("Remove", { pendingRemoval = root })
                        }
                    }
                }
            }
            HorizontalDivider(color = KanvasColors.Border)
            Text("Connect a source", style = MaterialTheme.typography.titleMedium)
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SourceCard("Local or USB", "Photos stored on your TV or a connected drive.", "Choose folder", onAddLocalFolder, Modifier.weight(1f))
                    SourceCard("Household NAS", "Your photo library, directly from your network.", "Connect NAS", onConnectHouseholdNas, Modifier.weight(1f))
                    SourceCard("Media server", "Discover a QNAP or other DLNA photo library.", "Find server", onAddQnap, Modifier.weight(1f))
                }
            } else {
                SourceCard("Local or USB", "Photos stored on your TV or a connected drive.", "Choose folder", onAddLocalFolder)
                SourceCard("Household NAS", "Your photo library, directly from your network.", "Connect NAS", onConnectHouseholdNas)
                SourceCard("Media server", "Discover a QNAP or other DLNA photo library.", "Find server", onAddQnap)
            }
        }
    }
    pendingRemoval?.let { root ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove this folder?") },
            text = { Text("Remove “${root.displayLabel}” from your collection? The original photos will stay in their folder.") },
            confirmButton = {
                KanvasButton("Remove folder", {
                    pendingRemoval = null
                    onRemoveRoot(root)
                })
            },
            dismissButton = { KanvasButton("Keep folder", { pendingRemoval = null }, primary = true) },
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SourceCard(title: String, description: String, action: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(description, color = KanvasColors.Muted, style = MaterialTheme.typography.bodySmall)
        KanvasButton(action, onClick, icon = Icons.Filled.Add)
    }
}
