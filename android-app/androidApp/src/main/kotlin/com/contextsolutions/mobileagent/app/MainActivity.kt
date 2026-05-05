package com.contextsolutions.mobileagent.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.contextsolutions.mobileagent.app.spike.SpikeActivity
import com.contextsolutions.mobileagent.app.ui.MainScreen
import com.contextsolutions.mobileagent.app.ui.theme.MobileAgentTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Hosts the M1 surface — a download flow that hands off to a minimal test-chat
 * once the model is present. The chat surface here is *not* the production chat
 * UI; that lands in WS-3/WS-11 with conversations, history, agent loop, and
 * tool calls. This activity exists to validate the WS-1 state machine
 * end-to-end on a real device.
 *
 * The Spike harness (M0 benchmark runner) is still launchable directly via
 * [SpikeActivity]; the chat top bar exposes a "Spike" action so we don't need
 * to relaunch via adb during M1 development.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Android 13+ requires the user to grant POST_NOTIFICATIONS at runtime; without
     * it, both the download progress notification AND the inference foreground-service
     * notification are silently suppressed. The FGS itself still runs (so generation
     * survives backgrounding), but the user can't see *that* it's running, which
     * defeats the trust signal the FGS notification is supposed to provide.
     *
     * We don't gate any UI on the result — denial just means a quieter UX. The
     * download/chat flows continue to work either way.
     */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result is informational only */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureNotificationPermission()
        setContent {
            MobileAgentTheme {
                MainScreen(
                    onOpenSpike = { startActivity(Intent(this, SpikeActivity::class.java)) },
                )
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
