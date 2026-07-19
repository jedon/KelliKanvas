package com.jedon.kellikanvas

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.MaterialTheme
import com.jedon.kellikanvas.permission.PermissionCoordinator
import com.jedon.kellikanvas.permission.PermissionRowId
import com.jedon.kellikanvas.permission.PermissionStatus
import com.jedon.kellikanvas.permission.ShellPermissionGate

class MainActivity : ComponentActivity() {
    private val permissionCoordinator by lazy { PermissionCoordinator(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            var sessionSkip by remember { mutableStateOf(false) }
            var permanentlyDenied by remember { mutableStateOf(emptySet<PermissionRowId>()) }
            var snapshot by remember { mutableStateOf(permissionCoordinator.snapshot()) }
            var pendingRowId by remember { mutableStateOf<PermissionRowId?>(null) }

            fun refreshSnapshot() {
                val next = permissionCoordinator.snapshot()
                permanentlyDenied =
                    permanentlyDenied.filter { rowId ->
                        next.rows.any { it.id == rowId && it.status == PermissionStatus.Denied }
                    }.toSet()
                snapshot = next
            }

            val permissionLauncher =
                rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    val rowId = pendingRowId
                    pendingRowId = null
                    if (!granted && rowId != null) {
                        val permission = permissionCoordinator.runtimePermission(rowId)
                        if (
                            permission != null &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(
                                this@MainActivity,
                                permission,
                            )
                        ) {
                            permanentlyDenied = permanentlyDenied + rowId
                        }
                    }
                    refreshSnapshot()
                }

            val settingsLauncher =
                rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult(),
                ) {
                    refreshSnapshot()
                }

            DisposableEffect(Unit) {
                val observer =
                    LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START) {
                            refreshSnapshot()
                        }
                    }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            MaterialTheme {
                if (permissionCoordinator.shouldDisplayGate(sessionSkip, snapshot)) {
                    ShellPermissionGate(
                        snapshot = snapshot,
                        permanentlyDenied = permanentlyDenied,
                        onGrant = { rowId ->
                            val permission = permissionCoordinator.runtimePermission(rowId)
                            if (permission == null) {
                                refreshSnapshot()
                                return@ShellPermissionGate
                            }
                            pendingRowId = rowId
                            permissionLauncher.launch(permission)
                        },
                        onOpenSettings = {
                            val intent =
                                permissionCoordinator.appSettingsIntent().apply {
                                    removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            settingsLauncher.launch(intent)
                        },
                        onNotNow = { sessionSkip = true },
                    )
                } else {
                    KelliKanvasNavHost((application as KelliKanvasApp).container)
                }
            }
        }
    }
}
