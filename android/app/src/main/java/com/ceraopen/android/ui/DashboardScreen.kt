// SPDX-License-Identifier: GPL-3.0-or-later
// The dashboard: the machine's face at the top, then cups, then the two
// settings the machine holds on its own (recipe and schedule).
package com.ceraopen.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ceraopen.android.CeraViewModel
import com.ceraopen.android.ServiceState
import com.ceraopen.android.StatsPeriod
import com.ceraopen.android.cera.BrewMode
import com.ceraopen.android.ui.theme.Tabular
import java.time.format.DateTimeFormatter
import java.util.Locale

private val STATE_LABEL = mapOf(
    "IDLE" to "Idle",
    "HEAT" to "Heating",
    "EXTRACT" to "Extracting",
    "HEAT_AND_EXTRACT" to "Heating & extracting",
)

private val BREW_MODES = listOf(BrewMode.HEAT, BrewMode.HEAT_AND_EXTRACT, BrewMode.EXTRACT)
private val BREW_LABEL = mapOf(
    BrewMode.HEAT to "Heat",
    BrewMode.HEAT_AND_EXTRACT to "Heat & extract",
    BrewMode.EXTRACT to "Extract",
)

private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val NEXT_START = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

@Composable
fun DashboardScreen(
    state: ServiceState,
    chart: StatsPeriod?,
    period: String,
    viewModel: CeraViewModel,
    onEditRecipe: () -> Unit,
    onEditSchedule: () -> Unit,
    onOpenFrames: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { StatusPanel(state, viewModel) }
        item {
            CupsPanel(
                state = state,
                chart = chart,
                period = period,
                onPeriod = { viewModel.setPeriod(it) },
                onPrev = { viewModel.previousPeriod() },
                onNext = { viewModel.nextPeriod() },
                onRefresh = { viewModel.refresh() },
            )
        }
        item { RecipePanel(state, onEditRecipe) }
        item { SchedulePanel(state, onEditSchedule) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onOpenFrames,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                ) {
                    Text("Frame log", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                Caption(versionLine(state))
            }
        }
    }
}

private fun versionLine(state: ServiceState): String {
    val versions = listOfNotNull(state.versionA, state.versionB).joinToString(" / ")
    return if (versions.isEmpty()) state.model ?: "" else "${state.model ?: "PCM03"} · $versions"
}

// ----------------------------------------------------------------- hero ----

@Composable
private fun StatusPanel(state: ServiceState, viewModel: CeraViewModel) {
    var mode by remember { mutableStateOf(BrewMode.HEAT_AND_EXTRACT) }
    var confirm by remember { mutableStateOf(false) }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = STATE_LABEL[state.stateText] ?: state.stateText,
                    style = MaterialTheme.typography.titleLarge,
                )
                val hint = statusHint(state)
                if (hint.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Caption(hint)
                }
            }
            BatteryIndicator(state.batteryRaw)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TemperatureGauge(
                tempC = state.waterTempC,
                targetC = state.targetC,
                modifier = Modifier.size(224.dp),
            )
        }
        Spacer(Modifier.height(20.dp))

        if (state.running) {
            Button(
                onClick = { viewModel.stop() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("Stop", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Caption("Stopping ends the shot; the machine stays heated.")
        } else {
            SegmentedRow(
                options = BREW_MODES.map { BREW_LABEL.getValue(it) },
                selectedIndex = BREW_MODES.indexOf(mode),
                enabled = state.controlEnabled,
                onSelect = { mode = BREW_MODES[it] },
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { confirm = true },
                enabled = state.controlEnabled,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = "Start ${BREW_LABEL.getValue(mode).lowercase()}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Caption(
                if (state.controlEnabled) {
                    "Closing the app while a shot runs sends STOP."
                } else {
                    "Controls are locked. Tap “Locked” in the header to unlock."
                },
            )
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Start ${BREW_LABEL.getValue(mode).lowercase()}?") },
            text = { Text("Water and cup in place, machine supervised.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    viewModel.start(mode)
                }) { Text("Start") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    }
}

private fun statusHint(state: ServiceState): String {
    val target = state.targetC?.let { "${Math.round(it)} °C" }
    return when (state.stateText) {
        // The title already says what the machine is doing.
        "HEAT", "EXTRACT", "HEAT_AND_EXTRACT" -> ""
        else -> {
            val temp = state.waterTempC
            val goal = state.targetC
            when {
                temp == null -> "Waiting for a reading"
                goal == null -> "Idle"
                temp >= Math.round(goal) - 1 -> "Ready to brew"
                else -> "Heating to $target"
            }
        }
    }
}

// ----------------------------------------------------------------- cups ----

@Composable
private fun CupsPanel(
    state: ServiceState,
    chart: StatsPeriod?,
    period: String,
    onPeriod: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRefresh: () -> Unit,
) {
    var selected by remember(chart?.period, chart?.offset) { mutableStateOf<Int?>(null) }
    val picked = selected?.let { chart?.bars?.getOrNull(it) }

    Panel {
        PanelHeader("Cups") {
            TextButton(
                onClick = onRefresh,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("Re-read", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        StatStrip(
            listOf(
                "Today" to state.cupsToday,
                "Week" to state.cupsWeek,
                "Month" to state.cupsMonth,
                "Year" to state.cupsYear,
            ),
        )
        Spacer(Modifier.height(16.dp))
        SegmentedRow(
            options = listOf("Week", "Month", "Year"),
            selectedIndex = listOf("week", "month", "year").indexOf(period).coerceAtLeast(0),
            onSelect = { onPeriod(listOf("week", "month", "year")[it]) },
        )
        Spacer(Modifier.height(14.dp))

        if (chart == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(196.dp),
                contentAlignment = Alignment.Center,
            ) {
                Caption(if (state.cupsToday == null) "No cup history yet." else "Reading cup history…")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(chart.title, style = MaterialTheme.typography.titleSmall)
                    state.cupsAsOf?.let {
                        Caption("as of ${it.format(DAY_MONTH)}")
                    }
                }
                Text(
                    text = chart.total.toString(),
                    style = MaterialTheme.typography.titleLarge.merge(Tabular),
                )
                Spacer(Modifier.width(4.dp))
                Caption("cups")
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onPrev, enabled = chart.canPrev) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous period")
                }
                IconButton(onClick = onNext, enabled = chart.canNext) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next period")
                }
            }
            Spacer(Modifier.height(10.dp))
            CupsBars(
                bars = chart.bars,
                selected = selected,
                onSelect = { selected = it },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = picked?.let { bar ->
                    val value = bar.cups?.let { "$it cups" } ?: "no data"
                    "${bar.name} · $value"
                } ?: "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (chart.partial) {
                Spacer(Modifier.height(4.dp))
                Caption("History starts ${chart.oldest.format(SHORT_DATE)}.")
            }
        }
    }
}

@Composable
private fun StatStrip(cells: List<Pair<String, Int?>>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 10.dp)
                        .width(1.dp)
                        .height(30.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = value?.toString() ?: "–",
                    style = MaterialTheme.typography.titleLarge.merge(Tabular),
                    maxLines = 1,
                )
            }
        }
    }
}

// --------------------------------------------------------------- recipe ----

@Composable
private fun RecipePanel(state: ServiceState, onEdit: () -> Unit) {
    Panel {
        PanelHeader("Recipe") {
            TextButton(
                onClick = onEdit,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("Edit", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(6.dp))
        SummaryRow("Water temperature", state.targetC?.let { "${Math.round(it)} °C" } ?: "–")
        RowRule()
        SummaryRow("Flow", state.flow?.toString() ?: "–")
        RowRule()
        SummaryRow("Presoak", state.presoakS?.let { "$it s" } ?: "–")
        RowRule()
        SummaryRow("Standstill", state.standstillS?.let { "$it s" } ?: "–")
        RowRule()
        SummaryRow("Extraction", state.extractionS?.let { "${Math.round(it)} s" } ?: "–")
        Spacer(Modifier.height(12.dp))
        Caption("Stored on the machine; the next shot uses these values.")
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.merge(Tabular),
        )
    }
}

// ------------------------------------------------------------- schedule ----

@Composable
private fun SchedulePanel(state: ServiceState, onEdit: () -> Unit) {
    val enabled = state.reservationEnabled == true
    Panel {
        PanelHeader("Schedule") {
            TextButton(
                onClick = onEdit,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(if (enabled) "Change" else "Set", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (!enabled) {
            Text("Nothing scheduled", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Caption("The machine can start by itself at a set time, with this app closed.")
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.reservationTime ?: "–",
                    style = MaterialTheme.typography.displayMedium.merge(Tabular),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.padding(bottom = 6.dp)) {
                    Text(
                        text = reservationLabel(state.reservationMode),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.reservationNext?.let {
                        Caption("Next start ${it.format(NEXT_START)}")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val extras = buildList {
                if (state.reservationAlarm == true) add("reminder on")
                state.reservationBeep?.let { add(beepLabel(it)) }
            }
            Caption(
                buildString {
                    append("Starts by itself; water and cup must be in place.")
                    if (extras.isNotEmpty()) {
                        append('\n')
                        append(extras.joinToString(", ").replaceFirstChar { it.uppercase() })
                        append('.')
                    }
                },
            )
        }
    }
}

private fun reservationLabel(mode: String?): String = when (mode) {
    "HEAT" -> "Heating"
    "EXTRACT" -> "Extract only"
    else -> "Heat & extract"
}

private fun beepLabel(name: String): String = when (name) {
    "DING_DONG" -> "ding dong"
    "DI_DI" -> "di di"
    "BU_GU" -> "bu gu"
    "BI_BI" -> "bi bi"
    else -> name.lowercase().replace('_', ' ')
}

