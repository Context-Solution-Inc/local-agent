package com.contextsolutions.mobileagent.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.contextsolutions.mobileagent.app.service.SessionState
import com.contextsolutions.mobileagent.inference.Accelerator

/**
 * One-shot prompt → streaming response surface for M1 WS-1 validation.
 *
 * Not the real chat UI (WS-11 builds that on top of WS-3's agent loop). This
 * screen exists to manually exercise:
 *   - Cold load on first prompt (4–8 s on Pixel 7)
 *   - Token streaming
 *   - Foreground-service lifecycle around generation (Decision 3 exit gate)
 *   - Idle unload at the configured timeout
 *   - Force unload (debug action) and subsequent reload
 *   - CPU-fallback degraded-mode banner
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSpike: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val session by viewModel.sessionState.collectAsState()
    var input by remember { mutableStateOf("") }
    val responseScroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("M1 test chat") },
                actions = {
                    TextButton(onClick = onOpenSpike) { Text("Spike") }
                    TextButton(onClick = { viewModel.forceUnload() }) { Text("Unload") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            SessionBanner(session)
            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(12.dp)
                    .verticalScroll(responseScroll),
            ) {
                when {
                    ui.error != null -> Text(
                        text = "Error: ${ui.error}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ui.response.isEmpty() && !ui.isGenerating -> Text(
                        text = "Type a prompt below and press Send. The first prompt " +
                            "will trigger a cold model load (4–8 s on Pixel 7).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Text(
                        text = ui.response,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            GenerationStatus(ui)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Prompt") },
                enabled = !ui.isGenerating,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.send(input)
                        input = ""
                    },
                    enabled = !ui.isGenerating && input.isNotBlank(),
                ) {
                    Text("Send")
                }
                if (ui.isGenerating) {
                    OutlinedButton(onClick = { viewModel.cancel() }) { Text("Cancel") }
                }
                Spacer(Modifier.width(0.dp))
            }
        }
    }
}

@Composable
private fun SessionBanner(state: SessionState) {
    val (text, isWarning) = when (state) {
        is SessionState.Unloaded ->
            "Model unloaded — next prompt cold-loads in 4–8 s." to false
        is SessionState.Loading ->
            "Loading model…" to false
        is SessionState.Loaded -> {
            val accel = state.activeAccelerator.name
            if (state.activeAccelerator == Accelerator.CPU) {
                "Loaded on CPU (degraded mode — generation will be slow)." to true
            } else {
                "Loaded on $accel." to false
            }
        }
        is SessionState.Failed ->
            "Model load failed: ${state.message}" to true
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun GenerationStatus(ui: ChatUiState) {
    when {
        ui.isGenerating -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (ui.tokens == 0) "Waiting for first token…"
                else "${ui.tokens} tokens",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ui.finishReason != null -> Text(
            text = "Finished: ${ui.finishReason} — ${ui.tokens} tokens",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
