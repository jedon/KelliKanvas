package com.jedon.kellikanvas.permission

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jedon.kellikanvas.R

@Composable
fun ShellPermissionGate(
    snapshot: PermissionSnapshot,
    permanentlyDenied: Set<PermissionRowId>,
    onGrant: (PermissionRowId) -> Unit,
    onOpenSettings: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.permission_gate_title),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.permission_gate_subtitle),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyLarge,
        )
        snapshot.rows.forEach { row ->
            PermissionRowCard(
                row = row,
                permanentlyDenied = row.id in permanentlyDenied,
                onGrant = { onGrant(row.id) },
                onOpenSettings = onOpenSettings,
            )
        }
        Button(onClick = onNotNow) {
            Text(text = stringResource(R.string.permission_not_now))
        }
    }
}

@Composable
private fun PermissionRowCard(
    row: PermissionRow,
    permanentlyDenied: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = rowTitle(row.id),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = rowStatusLabel(row.status),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when {
            row.status == PermissionStatus.Denied && permanentlyDenied -> {
                Button(onClick = onOpenSettings) {
                    Text(text = stringResource(R.string.permission_open_settings))
                }
            }
            row.status == PermissionStatus.Denied -> {
                Button(onClick = onGrant) {
                    Text(text = stringResource(R.string.permission_grant))
                }
            }
        }
    }
}

@Composable
private fun rowTitle(id: PermissionRowId): String = stringResource(
    when (id) {
        PermissionRowId.Internet -> R.string.permission_internet_title
        PermissionRowId.LocalNetwork -> R.string.permission_local_network_title
        PermissionRowId.ActivityRecognition -> R.string.permission_activity_title
        PermissionRowId.BodySensors -> R.string.permission_body_sensors_title
    },
)

@Composable
private fun rowStatusLabel(status: PermissionStatus): String = stringResource(
    when (status) {
        PermissionStatus.GrantedAtInstall -> R.string.permission_status_granted_at_install
        PermissionStatus.Granted -> R.string.permission_status_granted
        PermissionStatus.Denied -> R.string.permission_status_denied
        PermissionStatus.NotApplicable -> R.string.permission_status_not_applicable
    },
)
