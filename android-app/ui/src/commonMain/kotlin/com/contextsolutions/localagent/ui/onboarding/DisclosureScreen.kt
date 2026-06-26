package com.contextsolutions.localagent.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.ui.i18n.tr

/**
 * Onboarding screen 1 — on-device disclosure (PRD §6.1).
 *
 * Explains the privacy model in plain language; user must acknowledge
 * via checkbox before continuing. The checkbox is a deliberate friction
 * point — we want the user to read the text, not blindly tap Continue.
 */
@Composable
fun DisclosureScreen(
    onContinue: () -> Unit,
) {
    var acknowledged by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            // Tighter than the default so the longer disclosure copy + the
            // acknowledge checkbox + Continue all fit on one screen; the
            // verticalScroll above is the safety net on smaller devices.
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = tr(StringKeys.ONBOARDING_DISCLOSURE_TITLE),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = tr(StringKeys.ONBOARDING_DISCLOSURE_BODY),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = tr(StringKeys.ONBOARDING_DISCLOSURE_LEAVES_HEADER),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = tr(StringKeys.ONBOARDING_DISCLOSURE_LEAVES_BODY),
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = acknowledged,
                    onCheckedChange = { acknowledged = it },
                )
                Text(
                    text = tr(StringKeys.ONBOARDING_DISCLOSURE_ACKNOWLEDGE),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Button(
                onClick = onContinue,
                enabled = acknowledged,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr(StringKeys.ONBOARDING_NAV_CONTINUE))
            }
        }
    }
}
