package com.contextsolutions.localagent.voice

import com.contextsolutions.localagent.platform.IosJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS [TtsPreferences] (PR #41) — the read-aloud toggle persisted in an
 * [IosJsonStore] file, default `false` (matching every other platform). iOS picks
 * its voice through the OS, so the desktop-only voice config has no analogue here.
 */
class IosTtsPreferences(private val store: IosJsonStore) : TtsPreferences {

    private val state = MutableStateFlow(
        store.getString(KEY_ENABLED)?.toBooleanStrictOrNull() ?: false,
    )

    override fun isEnabled(): Boolean = state.value
    override fun enabledFlow(): Flow<Boolean> = state.asStateFlow()
    override fun setEnabled(enabled: Boolean) {
        if (state.value == enabled) return
        state.value = enabled
        store.putString(KEY_ENABLED, enabled.toString())
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}
