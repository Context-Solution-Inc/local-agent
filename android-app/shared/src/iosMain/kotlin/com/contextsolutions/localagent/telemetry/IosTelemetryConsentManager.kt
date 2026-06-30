package com.contextsolutions.localagent.telemetry

import com.contextsolutions.localagent.platform.IosJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS [TelemetryConsentManager] (PR #41), the counterpart of desktop's
 * [DesktopTelemetryConsentManager]. Two non-secret booleans — the opt-in toggle
 * (default OFF per PRD §3.2.1) and the first-run-decided flag — persisted in an
 * [IosJsonStore] file.
 */
class IosTelemetryConsentManager(private val store: IosJsonStore) : TelemetryConsentManager {

    private val enabledState = MutableStateFlow(
        store.getString(KEY_ENABLED)?.toBooleanStrictOrNull()
            ?: TelemetryConsentManager.DEFAULT_ENABLED,
    )
    private val firstRunState = MutableStateFlow(
        store.getString(KEY_FIRST_RUN_DECIDED)?.toBooleanStrictOrNull() ?: false,
    )

    override fun enabled(): Boolean = enabledState.value

    override fun enabledFlow(): Flow<Boolean> = enabledState.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        enabledState.value = enabled
        store.putString(KEY_ENABLED, enabled.toString())
    }

    override fun firstRunDecided(): Boolean = firstRunState.value

    override fun firstRunDecidedFlow(): Flow<Boolean> = firstRunState.asStateFlow()

    override fun markFirstRunDecided() {
        if (firstRunState.value) return // idempotent
        firstRunState.value = true
        store.putString(KEY_FIRST_RUN_DECIDED, "true")
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_FIRST_RUN_DECIDED = "first_run_decided"
    }
}
