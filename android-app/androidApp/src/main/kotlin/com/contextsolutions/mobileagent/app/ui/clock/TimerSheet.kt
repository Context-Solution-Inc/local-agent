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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.contextsolutions.mobileagent.clock.TimerEntry
import kotlinx.coroutines.delay

/**
 * Bottom sheet listing active timers + a small form to start a new one.
 * Each row shows a live-ticking remaining countdown driven by a 1 s
 * `LaunchedEffect` — pure local state, no service ticks needed.
 *
 * "Add 1 min" / "Add 5 min" buttons call into the ViewModel which extends
 * `fireAtEpochMs` and re-arms AlarmManager. Cancel removes the row + arm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerSheet(
    onDismiss: () -> Unit,
    viewModel: ClockViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val timers by viewModel.timers.collectAsState()

    // Tick a local clock once a second so the per-row remaining-time labels
    // update without each row spinning up its own LaunchedEffect.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Timers",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            if (timers.isEmpty()) {
                Text(
                    text = "No active timers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(timers, key = { it.id }) { timer ->
                        TimerRow(
                            timer = timer,
                            nowMs = nowMs,
                            onExtend = { extraMs -> viewModel.extendTimer(timer.id, extraMs) },
                            onCancel = { viewModel.cancelTimer(timer.id) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            NewTimerForm(
                onCreate = { ms, label ->
                    viewModel.createTimer(ms, label)
                },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TimerRow(
    timer: TimerEntry,
    nowMs: Long,
    onExtend: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    val remainingMs = (timer.fireAtEpochMs - nowMs).coerceAtLeast(0)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = timer.label?.takeIf { it.isNotBlank() } ?: "Timer",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = formatHms(remainingMs),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onExtend(60_000L) }) { Text("+1 min") }
            OutlinedButton(onClick = { onExtend(5 * 60_000L) }) { Text("+5 min") }
        }
    }
}

@Composable
private fun NewTimerForm(onCreate: (Long, String?) -> Unit) {
    var hours by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "New timer",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DurationField(value = hours, onValueChange = { hours = it }, label = "h", modifier = Modifier.weight(1f))
            DurationField(value = minutes, onValueChange = { minutes = it }, label = "m", modifier = Modifier.weight(1f))
            DurationField(value = seconds, onValueChange = { seconds = it }, label = "s", modifier = Modifier.weight(1f))
        }
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Label (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val ms = ((hours.toIntOrNull() ?: 0) * 3600L +
                    (minutes.toIntOrNull() ?: 0) * 60L +
                    (seconds.toIntOrNull() ?: 0)) * 1000L
                if (ms > 0) {
                    onCreate(ms, label.takeIf { it.isNotBlank() })
                    hours = ""; minutes = ""; seconds = ""; label = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start timer")
        }
    }
}

@Composable
private fun DurationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            // Numeric-only, 0..99 for any single field (alarm/timer max is hours).
            val filtered = input.filter(Char::isDigit).take(2)
            onValueChange(filtered)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private fun formatHms(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
