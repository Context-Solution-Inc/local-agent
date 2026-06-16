package com.contextsolutions.localagent.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.ui.i18n.tr

/**
 * Onboarding screen 3 — anonymous telemetry consent (PRD §3.2.1, §4.4).
 *
 * Default OFF — user must actively tap "Help improve the assistant" to
 * opt in. The "Skip" button records the decision (so we don't nag on
 * next launch) but leaves the toggle off. Both buttons mark
 * `TelemetryConsentManager.firstRunDecided = true` so the host can
 * advance to Complete.
 *
 * Copy carefully enumerates what is and isn't transmitted — same
 * language the Settings surface uses + the privacy policy mirrors.
 */
@Composable
fun TelemetryConsentScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = tr(StringKeys.ONBOARDING_TELEMETRY_TITLE),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )

            Text(
                text = tr(StringKeys.ONBOARDING_TELEMETRY_BODY),
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = tr(StringKeys.ONBOARDING_TELEMETRY_SEND_HEADER),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = tr(StringKeys.ONBOARDING_TELEMETRY_SEND_BODY),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = tr(StringKeys.ONBOARDING_TELEMETRY_NEVER_HEADER),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = tr(StringKeys.ONBOARDING_TELEMETRY_NEVER_BODY),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr(StringKeys.ONBOARDING_TELEMETRY_ACCEPT))
            }

            OutlinedButton(
                onClick = onDecline,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr(StringKeys.ONBOARDING_TELEMETRY_DECLINE))
            }
        }
    }
}
