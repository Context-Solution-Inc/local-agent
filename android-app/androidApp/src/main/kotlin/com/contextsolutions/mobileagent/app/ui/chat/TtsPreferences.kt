package com.contextsolutions.mobileagent.app.ui.chat

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent on/off state for the read-aloud (text-to-speech) speaker toggle.
 * Default `false` — the app stays silent until the user opts in. UI-only
 * preference, so the Android impl lives in this file (mirrors
 * [com.contextsolutions.mobileagent.app.ui.theme.ThemePreferences]); no need to
 * expose it to the KMP shared module.
 */
interface TtsPreferences {
    fun isEnabled(): Boolean
    fun enabledFlow(): Flow<Boolean>
    fun setEnabled(enabled: Boolean)
}

class SharedPreferencesTtsPreferences(context: Context) : TtsPreferences {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))

    override fun isEnabled(): Boolean = state.value
    override fun enabledFlow(): Flow<Boolean> = state.asStateFlow()
    override fun setEnabled(enabled: Boolean) {
        if (state.value == enabled) return
        state.value = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFS_NAME = "tts"
        const val KEY_ENABLED = "enabled"
    }
}
