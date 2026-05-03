package com.contextsolutions.mobileagent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.contextsolutions.mobileagent.app.spike.SpikeActivity
import com.contextsolutions.mobileagent.app.ui.theme.MobileAgentTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * M0 placeholder. The real chat UI lands in M1 (WS-11). For now, MainActivity exists
 * so the app installs and launches; the only useful surface is the button that opens
 * the spike harness.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MobileAgentTheme {
                M0Placeholder(
                    onOpenSpike = {
                        startActivity(android.content.Intent(this, SpikeActivity::class.java))
                    }
                )
            }
        }
    }
}

@Composable
private fun M0Placeholder(onOpenSpike: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Mobile Agent — M0 build",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Chat UI lands in M1. Use the spike harness to validate the inference path.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenSpike) { Text("Open M0 Inference Spike") }
        }
    }
}
