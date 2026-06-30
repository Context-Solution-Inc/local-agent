package com.contextsolutions.localagent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * iOS actual (PR #41): dictation is deferred this milestone, so the device reports
 * dictation unavailable — the mic button is disabled. A real
 * `SFSpeechRecognizer`-backed dictation path is a follow-up.
 */
@Composable
actual fun rememberMicPermission(): MicPermission = remember {
    object : MicPermission {
        override val available: Boolean = false
        override val granted: Boolean = false
        override fun request(onResult: (Boolean) -> Unit) = onResult(false)
    }
}
