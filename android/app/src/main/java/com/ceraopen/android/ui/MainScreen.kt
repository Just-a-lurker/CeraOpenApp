// SPDX-License-Identifier: GPL-3.0-or-later
// The app shell: the connect screen when there is no machine, the dashboard
// when there is one, and the header both share.
package com.ceraopen.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ceraopen.android.CeraViewModel
import com.ceraopen.android.ServiceState
import com.ceraopen.android.cera.Found
import com.ceraopen.android.hasBluetoothPermissions

private enum class Sheet { None, Recipe, Schedule, Frames }

@Composable
fun MainScreen(
    onRequestPermissions: () -> Unit = {},
    viewModel: CeraViewModel = viewModel(),
) {
    val state by viewModel.serviceState.collectAsStateWithLifecycle()
    val frames by viewModel.frames.collectAsStateWithLifecycle()
    val chart by viewModel.chart.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackHost = remember { SnackbarHostState() }
    var sheet by remember { mutableStateOf(Sheet.None) }

    LaunchedEffect(message) {
        message?.let {
            snackHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackHost) },
        topBar = {
            if (state.connected) {
                AppHeader(
                    state = state,
                    onToggleLock = { viewModel.setControl(!state.controlEnabled) },
                    onRefresh = { viewModel.refresh() },
                    onFrames = { sheet = Sheet.Frames },
                    onDisconnect = { viewModel.disconnect() },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.connected) {
                DashboardScreen(
                    state = state,
                    chart = chart,
                    period = period,
                    viewModel = viewModel,
                    onEditRecipe = { sheet = Sheet.Recipe },
                    onEditSchedule = { sheet = Sheet.Schedule },
                    onOpenFrames = { sheet = Sheet.Frames },
                )
            } else {
                ConnectScreen(
                    state = state,
                    viewModel = viewModel,
                    onRequestPermissions = onRequestPermissions,
                )
            }
        }
    }

    when (sheet) {
        Sheet.None -> Unit
        Sheet.Recipe -> RecipeSheet(
            state = state,
            onDismiss = { sheet = Sheet.None },
            onSave = { fields, target ->
                viewModel.saveRecipe(fields, target)
                sheet = Sheet.None
            },
        )
        Sheet.Schedule -> ScheduleSheet(
            state = state,
            onDismiss = { sheet = Sheet.None },
            onSchedule = { mode, time, alarm, beep ->
                viewModel.setReservation(mode, time, alarm, beep)
                sheet = Sheet.None
            },
            onCancel = {
                viewModel.cancelReservation()
                sheet = Sheet.None
            },
        )
        Sheet.Frames -> FrameLogSheet(
            frames = frames,
            onDismiss = { sheet = Sheet.None },
        )
    }
}

// ---------------------------------------------------------------- header ----

@Composable
private fun AppHeader(
    state: ServiceState,
    onToggleLock: () -> Unit,
    onRefresh: () -> Unit,
    onFrames: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("CeraOpen", style = MaterialTheme.typography.titleLarge)
                Caption(state.model ?: state.address ?: "Connected")
            }
            LockPill(locked = !state.controlEnabled, onToggle = onToggleLock)
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Re-read machine") },
                        onClick = { menu = false; onRefresh() },
                    )
                    DropdownMenuItem(
                        text = { Text("Bluetooth frame log") },
                        onClick = { menu = false; onFrames() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Disconnect") },
                        onClick = { menu = false; onDisconnect() },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Replaces the giant Material switch: a pill that says what it does. */
@Composable
private fun LockPill(locked: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(
                        color = if (locked) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                        shape = CircleShape,
                    ),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (locked) "Locked" else "Unlocked",
                style = MaterialTheme.typography.labelMedium,
                color = if (locked) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

// --------------------------------------------------------------- connect ----

@Composable
private fun ConnectScreen(
    state: ServiceState,
    viewModel: CeraViewModel,
    onRequestPermissions: () -> Unit,
) {
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val results by viewModel.scanResults.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(84.dp))
        Text("CeraOpen", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "A remote for your portable espresso machine.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))

        Panel {
            Text("No machine connected", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Caption(
                "Close the HappyGo app first: the machine accepts one connection at a time. " +
                    "Connecting takes about ten seconds.",
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (hasBluetoothPermissions(context)) {
                        viewModel.scanForMachines()
                    } else {
                        onRequestPermissions()
                    }
                },
                enabled = !scanning && !state.connecting,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (scanning || state.connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(if (scanning) "Scanning…" else "Connecting…")
                } else {
                    Text("Scan for machines")
                }
            }
            if (state.connecting) {
                Spacer(Modifier.height(10.dp))
                Caption("The machine often ignores the first attempt; the app retries on its own.")
            }
        }

        val found = results
        if (found != null) {
            Spacer(Modifier.height(14.dp))
            Panel {
                if (found.isEmpty()) {
                    Text("No machines found", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Caption("Is the machine switched on, and HappyGo closed?")
                } else {
                    Text("Found nearby", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    found.forEachIndexed { index, device ->
                        if (index > 0) RowRule(Modifier.padding(vertical = 2.dp))
                        DeviceRow(device) { viewModel.connect(device.address) }
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Caption("Bluetooth is used only to talk to the machine.")
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun DeviceRow(device: Found, onConnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.name.ifEmpty { "Unnamed machine" },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (device.likelyMachine) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = "Your machine",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Caption("${device.address} · ${device.rssi} dBm")
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Connect to ${device.name}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
