// SPDX-License-Identifier: GPL-3.0-or-later
// The three editing surfaces, all bottom sheets: the recipe, the schedule,
// and the Bluetooth frame log. The dashboard stays a readout; edits are a
// deliberate, modal task.
package com.ceraopen.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ceraopen.android.FrameView
import com.ceraopen.android.ServiceState
import com.ceraopen.android.cera.Beep
import com.ceraopen.android.cera.ReservationMode
import com.ceraopen.android.ui.theme.Tabular
import java.util.Locale

// --------------------------------------------------------------- recipe ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeSheet(
    state: ServiceState,
    onDismiss: () -> Unit,
    onSave: (Map<String, Int>, Double?) -> Unit,
) {
    // The draft re-seeds whenever the machine reports new stored values.
    var target by remember(state.targetC) {
        mutableIntStateOf(state.targetC?.let { Math.round(it).toInt() } ?: 90)
    }
    var flow by remember(state.flow) { mutableIntStateOf(state.flow ?: 8) }
    var presoak by remember(state.presoakS) { mutableIntStateOf(state.presoakS ?: 0) }
    var standstill by remember(state.standstillS) { mutableIntStateOf(state.standstillS ?: 0) }
    var extractionRaw by remember(state.extractionRaw) { mutableIntStateOf(state.extractionRaw ?: 120) }

    val storedTarget = state.targetC?.let { Math.round(it).toInt() }
    val dirty =
        (state.flow != null && state.flow != flow) ||
            (state.presoakS != null && state.presoakS != presoak) ||
            (state.standstillS != null && state.standstillS != standstill) ||
            (state.extractionRaw != null && state.extractionRaw != extractionRaw) ||
            (storedTarget != null && storedTarget != target)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Recipe", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Caption("Written to the machine when you save; the next shot uses these values.")

            Spacer(Modifier.height(20.dp))
            SliderField(
                label = "Water temperature",
                value = target,
                range = 38..95,
                display = "$target °C",
                hint = "38–95 °C",
                enabled = state.controlEnabled,
            ) { target = it }
            SliderField(
                label = "Flow",
                value = flow,
                range = 1..15,
                display = flow.toString(),
                hint = "1 (min) to 15 (max)",
                enabled = state.controlEnabled,
            ) { flow = it }

            RowRule()
            StepperField(
                label = "Presoak",
                value = "$presoak s",
                canDecrease = presoak > 0,
                canIncrease = presoak < 30,
                enabled = state.controlEnabled,
                onDecrease = { presoak = (presoak - 1).coerceAtLeast(0) },
                onIncrease = { presoak = (presoak + 1).coerceAtMost(30) },
                description = "Presoak",
            )
            RowRule()
            StepperField(
                label = "Standstill",
                value = "$standstill s",
                canDecrease = standstill > 0,
                canIncrease = standstill < 30,
                enabled = state.controlEnabled,
                onDecrease = { standstill = (standstill - 1).coerceAtLeast(0) },
                onIncrease = { standstill = (standstill + 1).coerceAtMost(30) },
                description = "Standstill",
            )
            RowRule()
            StepperField(
                label = "Extraction",
                value = "${extractionRaw / 2 + 60} s",
                canDecrease = extractionRaw > 0,
                canIncrease = extractionRaw < 240,
                enabled = state.controlEnabled,
                onDecrease = { extractionRaw = (extractionRaw - 20).coerceAtLeast(0) },
                onIncrease = { extractionRaw = (extractionRaw + 20).coerceAtMost(240) },
                description = "Extraction time",
            )
            Spacer(Modifier.height(6.dp))
            Caption("Extraction moves in 10-second steps; the figure is an estimate.")

            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (dirty) "Unsaved changes" else "No changes",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (dirty) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        target = storedTarget ?: target
                        flow = state.flow ?: flow
                        presoak = state.presoakS ?: presoak
                        standstill = state.standstillS ?: standstill
                        extractionRaw = state.extractionRaw ?: extractionRaw
                    },
                    enabled = dirty,
                ) { Text("Discard") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val fields = mutableMapOf<String, Int>()
                        if (state.flow != null && state.flow != flow) fields["flow"] = flow
                        if (state.presoakS != null && state.presoakS != presoak) {
                            fields["presoak_s"] = presoak
                        }
                        if (state.standstillS != null && state.standstillS != standstill) {
                            fields["standstill_s"] = standstill
                        }
                        if (state.extractionRaw != null && state.extractionRaw != extractionRaw) {
                            fields["extraction_raw"] = extractionRaw
                        }
                        val newTarget = if (storedTarget != null && storedTarget != target) {
                            target.toDouble()
                        } else {
                            null
                        }
                        onSave(fields, newTarget)
                    },
                    enabled = state.controlEnabled && dirty,
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Save") }
            }
            if (!state.controlEnabled) {
                Spacer(Modifier.height(8.dp))
                Caption("The controls are locked; unlock in the header to write changes.")
            }
        }
    }
}

@Composable
private fun SliderField(
    label: String,
    value: Int,
    range: IntRange,
    display: String,
    hint: String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.titleMedium.merge(Tabular))
        }
        ValueSlider(
            value = value,
            range = range,
            onChange = onChange,
            description = label,
            enabled = enabled,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StepperField(
    label: String,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    description: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Stepper(
            display = value,
            canDecrease = canDecrease,
            canIncrease = canIncrease,
            enabled = enabled,
            onDecrease = onDecrease,
            onIncrease = onIncrease,
            description = description,
        )
    }
}

// ------------------------------------------------------------- schedule ----

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleSheet(
    state: ServiceState,
    onDismiss: () -> Unit,
    onSchedule: (ReservationMode, String, Boolean, Beep) -> Unit,
    onCancel: () -> Unit,
) {
    var mode by remember(state.reservationMode) {
        mutableStateOf(
            ReservationMode.entries.firstOrNull { it.name == state.reservationMode }
                ?: ReservationMode.HEAT_AND_EXTRACT,
        )
    }
    var time by remember(state.reservationTime) { mutableStateOf(state.reservationTime ?: "07:00") }
    var alarm by remember(state.reservationAlarm) { mutableStateOf(state.reservationAlarm ?: false) }
    var beep by remember(state.reservationBeep) {
        mutableStateOf(
            Beep.entries.firstOrNull { it.name == state.reservationBeep } ?: Beep.DING_DONG,
        )
    }
    var showPicker by remember { mutableStateOf(false) }
    val enabled = state.reservationEnabled == true

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Schedule a start", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Caption(
                "The machine starts by itself on its own clock, even with this app closed. " +
                    "A time already passed today runs tomorrow.",
            )

            Spacer(Modifier.height(20.dp))
            Text("Mode", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            SegmentedRow(
                options = listOf("Heat & extract", "Heating", "Extract"),
                selectedIndex = when (mode) {
                    ReservationMode.HEAT_AND_EXTRACT -> 0
                    ReservationMode.HEAT -> 1
                    ReservationMode.EXTRACT -> 2
                },
                onSelect = {
                    mode = when (it) {
                        0 -> ReservationMode.HEAT_AND_EXTRACT
                        1 -> ReservationMode.HEAT
                        else -> ReservationMode.EXTRACT
                    }
                },
            )

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Time", style = MaterialTheme.typography.bodyLarge)
                    Caption("On the machine's clock, 24-hour")
                }
                Surface(
                    onClick = { showPicker = true },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = time,
                            style = MaterialTheme.typography.headlineSmall.merge(Tabular),
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Change time",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Reminder", style = MaterialTheme.typography.bodyLarge)
                    Caption("The machine chimes shortly before it starts.")
                }
                Switch(checked = alarm, onCheckedChange = { alarm = it })
            }

            Spacer(Modifier.height(12.dp))
            Text("Sound", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Beep.DING_DONG to "Ding dong",
                    Beep.DI_DI to "Di di",
                    Beep.BU_GU to "Bu gu",
                    Beep.BI_BI to "Bi bi",
                ).forEach { (option, label) ->
                    FilterChip(
                        selected = beep == option,
                        onClick = { beep = option },
                        shape = MaterialTheme.shapes.small,
                        leadingIcon = if (beep == option) {
                            {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        } else {
                            null
                        },
                        label = {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (enabled) {
                    OutlinedButton(
                        onClick = onCancel,
                        enabled = state.controlEnabled,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text("Cancel schedule") }
                }
                Button(
                    onClick = { onSchedule(mode, time, alarm, beep) },
                    enabled = state.controlEnabled,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(if (enabled) "Update" else "Schedule") }
            }
            Spacer(Modifier.height(10.dp))
            Caption("Water and cup must be in place before it starts.")
            if (!state.controlEnabled) {
                Spacer(Modifier.height(6.dp))
                Caption("The controls are locked; unlock in the header to schedule.")
            }
        }
    }

    if (showPicker) {
        val (hour, minute) = parseTime(time)
        val picker = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Start time") },
            text = { TimeInput(state = picker) },
            confirmButton = {
                TextButton(
                    onClick = {
                        time = String.format(Locale.US, "%02d:%02d", picker.hour, picker.minute)
                        showPicker = false
                    },
                ) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        )
    }
}

private fun parseTime(t: String): Pair<Int, Int> {
    val parts = t.split(":")
    val hour = (parts.getOrNull(0)?.toIntOrNull() ?: 7).coerceIn(0, 23)
    val minute = (parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59)
    return hour to minute
}

// ---------------------------------------------------------------- frames ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameLogSheet(frames: List<FrameView>, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Bluetooth frames", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Caption("Newest first. For diagnosing the link, not for daily use.")
            Spacer(Modifier.height(12.dp))
            if (frames.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Caption("No frames yet. Connect to see traffic.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
                ) {
                    items(frames.asReversed()) { frame ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = frame.direction,
                                style = MaterialTheme.typography.labelSmall.merge(Tabular),
                                color = if (frame.direction == "TX") {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.width(26.dp),
                            )
                            MonoLine(
                                text = frame.hex,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "#${frame.seq}",
                                style = MaterialTheme.typography.labelSmall.merge(Tabular),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
