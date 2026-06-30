package com.contextsolutions.localagent.onboarding

import com.contextsolutions.localagent.platform.IosJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS [OnboardingPreferences] (PR #41), the counterpart of desktop's
 * [DesktopOnboardingPreferences]. The first-run language gate persisted as a
 * plain boolean in an [IosJsonStore] file.
 */
class IosOnboardingPreferences(private val store: IosJsonStore) : OnboardingPreferences {

    private val languageState = MutableStateFlow(readBool(KEY_LANGUAGE_DECIDED))

    override fun languageDecided(): Boolean = languageState.value
    override fun languageDecidedFlow(): Flow<Boolean> = languageState.asStateFlow()
    override fun markLanguageDecided() = mark(languageState, KEY_LANGUAGE_DECIDED)

    private fun readBool(key: String): Boolean =
        store.getString(key)?.toBooleanStrictOrNull() ?: false

    private fun mark(state: MutableStateFlow<Boolean>, key: String) {
        if (state.value) return // idempotent
        state.value = true
        store.putString(key, "true")
    }

    private companion object {
        const val KEY_LANGUAGE_DECIDED = "language_decided"
    }
}
