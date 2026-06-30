package com.contextsolutions.localagent.memory

import com.contextsolutions.localagent.platform.IosJsonStore

/**
 * iOS [MemoryPreferences] (PR #41) — the memory-creation toggle persisted in a
 * small JSON file, the counterpart of desktop's [DesktopMemoryPreferences].
 */
class IosMemoryPreferences(private val store: IosJsonStore) : MemoryPreferences {

    override fun creationEnabled(): Boolean =
        store.getString(KEY_CREATION_ENABLED)?.toBooleanStrictOrNull()
            ?: MemoryPreferences.DEFAULT_CREATION_ENABLED

    override fun setCreationEnabled(enabled: Boolean) {
        store.putString(KEY_CREATION_ENABLED, enabled.toString())
    }

    private companion object {
        const val KEY_CREATION_ENABLED = "creation_enabled"
    }
}
