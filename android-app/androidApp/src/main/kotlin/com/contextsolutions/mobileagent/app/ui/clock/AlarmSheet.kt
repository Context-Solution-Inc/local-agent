package com.contextsolutions.mobileagent.app.ui.clock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.contextsolutions.mobileagent.clock.AlarmDay
import com.contextsolutions.mobileagent.clock.AlarmEntry

/**
 * Bottom sheet listing scheduled alarms + a form to add a new one.
 *
 * Each row offers:
 *  - Enable/disable Switch (recurring alarms only — one-shot is on by definition)
 *  - "Edit" → expands into an inline time picker + day chips
 *  - "Cancel" → removes the row
 *
 * The new-alarm form has the same time picker + day-chip set + label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmSheet(
    onDismiss: () -> Unit,
    viewModel: ClockViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val alarms by viewModel.alarms.collectAsState()
    var editingId by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Alarms", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (alarms.isEmpty()) {
                Text(
                    text = "No alarms scheduled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmRow(
                            alarm = alarm,
                            expanded = editingId == alarm.id,
                            onToggleExpanded = {
                                editingId = if (editingId == alarm.id) null else alarm.id
                            },
                            onToggleEnabled = { viewModel.setAlarmEnabled(alarm.id, it) },
                            onCancel = {
                                if (editingId == alarm.id) editingId = null
                                viewModel.cancelAlarm(alarm.id)
                            },
                            onUpdate = { updated ->
                                viewModel.updateAlarm(updated)
                                editingId = null
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Hide the "New alarm" form while the user is editing an
            // existing alarm — the inline editor already fills the same
            // role and showing both is just visual noise.
            if (editingId == null) {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                NewAlarmForm(
                    onCreate = { hour, minute, days, label ->
                        viewModel.createAlarm(hour, minute, days, label)
                    },
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmRow(
    alarm: AlarmEntry,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onUpdate: (AlarmEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "%02d:%02d".format(alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.titleMedium,
                )
                val subtitle = buildString {
                    if (alarm.label?.isNotBlank() == true) {
                        append(alarm.label); append(" · ")
                    }
                    append(
                        if (alarm.isRecurring) alarm.recurringDays.toLabel() else "Once",
                    )
                    if (!alarm.enabled) append(" · off")
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggleEnabled)
            TextButton(onClick = onToggleExpanded) {
                Text(if (expanded) "Done" else "Edit")
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        if (expanded) {
            EditAlarmForm(
                initial = alarm,
                onSubmit = onUpdate,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditAlarmForm(initial: AlarmEntry, onSubmit: (AlarmEntry) -> Unit) {
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = false,
    )
    var days by remember { mutableStateOf(initial.recurringDays) }
    var label by remember { mutableStateOf(initial.label ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        TimePicker(state = timeState)
        DaysChips(selected = days, onChange = { days = it })
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Label (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                onSubmit(
                    initial.copy(
                        hour = timeState.hour,
                        minute = timeState.minute,
                        recurringDays = days,
                        label = label.takeIf { it.isNotBlank() },
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save changes")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewAlarmForm(onCreate: (Int, Int, Set<AlarmDay>, String?) -> Unit) {
    val timeState = rememberTimePickerState(initialHour = 7, initialMinute = 0, is24Hour = false)
    var days by remember { mutableStateOf<Set<AlarmDay>>(emptySet()) }
    var label by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("New alarm", style = MaterialTheme.typography.titleSmall)
        TimePicker(state = timeState)
        DaysChips(selected = days, onChange = { days = it })
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Label (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                onCreate(timeState.hour, timeState.minute, days, label.takeIf { it.isNotBlank() })
                days = emptySet()
                label = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add alarm")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaysChips(selected: Set<AlarmDay>, onChange: (Set<AlarmDay>) -> Unit) {
    // Sun-first ordering matches the US calendar convention and gives the
    // canonical S M T W T F S row layout. Tue/Thu and Sat/Sun share a
    // first letter; positional context disambiguates per the standard
    // alarm-clock UI pattern.
    val order = listOf(
        AlarmDay.SUNDAY, AlarmDay.MONDAY, AlarmDay.TUESDAY, AlarmDay.WEDNESDAY,
        AlarmDay.THURSDAY, AlarmDay.FRIDAY, AlarmDay.SATURDAY,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // One-tap presets. Tapping a preset replaces the current selection
        // outright — matches the user's mental model of "pick a pattern, then
        // tweak". The preset's selected styling activates when its exact
        // set is currently chosen so the user can see which preset their
        // current selection matches (or that they've diverged from any).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip(
                label = "Weekdays",
                isActive = selected == WEEKDAYS,
                onClick = { onChange(WEEKDAYS) },
            )
            PresetChip(
                label = "Weekends",
                isActive = selected == WEEKENDS,
                onClick = { onChange(WEEKENDS) },
            )
            PresetChip(
                label = "Every day",
                isActive = selected == ALL_DAYS,
                onClick = { onChange(ALL_DAYS) },
            )
        }
        // Per-day toggles. weight(1f) on each chip splits the row width evenly
        // so all seven fit without horizontal overflow at Pixel 7 width.
        // FlowRow is avoided (BOM 2024.12.01 foundation-layout runtime/compile
        // ABI skew on the new FlowRowOverflow param).
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (day in order) {
                FilterChip(
                    selected = day in selected,
                    onClick = {
                        onChange(if (day in selected) selected - day else selected + day)
                    },
                    // Single-letter labels centered inside the chip. The
                    // label slot otherwise start-aligns the text, which
                    // looks off-balance once weight(1f) stretches the
                    // chip wider than the letter. fillMaxWidth on the
                    // Text plus textAlign=Center pushes the glyph to the
                    // visual middle of the chip's content area.
                    label = {
                        Text(
                            text = day.singleLetter(),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetChip(label: String, isActive: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        colors = if (isActive) {
            androidx.compose.material3.AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            androidx.compose.material3.AssistChipDefaults.assistChipColors()
        },
    )
}

private val WEEKDAYS: Set<AlarmDay> = setOf(
    AlarmDay.MONDAY, AlarmDay.TUESDAY, AlarmDay.WEDNESDAY, AlarmDay.THURSDAY, AlarmDay.FRIDAY,
)
private val WEEKENDS: Set<AlarmDay> = setOf(AlarmDay.SATURDAY, AlarmDay.SUNDAY)
private val ALL_DAYS: Set<AlarmDay> = WEEKDAYS + WEEKENDS

private fun AlarmDay.singleLetter(): String = when (this) {
    AlarmDay.SUNDAY -> "S"
    AlarmDay.MONDAY -> "M"
    AlarmDay.TUESDAY -> "T"
    AlarmDay.WEDNESDAY -> "W"
    AlarmDay.THURSDAY -> "T"
    AlarmDay.FRIDAY -> "F"
    AlarmDay.SATURDAY -> "S"
}

private fun AlarmDay.shortName(): String = when (this) {
    AlarmDay.MONDAY -> "Mon"
    AlarmDay.TUESDAY -> "Tue"
    AlarmDay.WEDNESDAY -> "Wed"
    AlarmDay.THURSDAY -> "Thu"
    AlarmDay.FRIDAY -> "Fri"
    AlarmDay.SATURDAY -> "Sat"
    AlarmDay.SUNDAY -> "Sun"
}

private fun Set<AlarmDay>.toLabel(): String {
    if (isEmpty()) return "Once"
    return when (this) {
        WEEKDAYS -> "Weekdays"
        WEEKENDS -> "Weekends"
        ALL_DAYS -> "Every day"
        else -> joinToString(", ") { it.shortName() }
    }
}
