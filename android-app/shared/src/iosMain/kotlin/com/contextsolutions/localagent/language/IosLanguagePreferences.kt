package com.contextsolutions.localagent.language

import com.contextsolutions.localagent.platform.IosJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS [LanguagePreferences] (PR #41), the counterpart of desktop's
 * [DesktopLanguagePreferences]. The stored value is the ISO 639-1 code;
 * [PreferredLanguage.fromCode] falls back to [PreferredLanguage.DEFAULT] for
 * unknown/missing codes. Backed by an [IosJsonStore] file.
 */
class IosLanguagePreferences(private val store: IosJsonStore) : LanguagePreferences {

    private val state = MutableStateFlow(
        PreferredLanguage.fromCode(store.getString(KEY_LANGUAGE_CODE)),
    )

    override fun preferredLanguage(): PreferredLanguage = state.value

    override fun preferredLanguageFlow(): Flow<PreferredLanguage> = state.asStateFlow()

    override fun setPreferredLanguage(language: PreferredLanguage) {
        if (state.value == language) return // idempotent
        state.value = language
        store.putString(KEY_LANGUAGE_CODE, language.code)
    }

    private companion object {
        const val KEY_LANGUAGE_CODE = "language_code"
    }
}
