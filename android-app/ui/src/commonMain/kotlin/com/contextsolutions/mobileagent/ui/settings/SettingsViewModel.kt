package com.contextsolutions.mobileagent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.mobileagent.inference.OllamaClient
import com.contextsolutions.mobileagent.inference.OllamaModel
import com.contextsolutions.mobileagent.language.LanguagePreferences
import com.contextsolutions.mobileagent.language.PreferredLanguage
import com.contextsolutions.mobileagent.memory.MemoryPreferences
import com.contextsolutions.mobileagent.memory.MemoryStore
import com.contextsolutions.mobileagent.observability.SafeCrashReporter
import com.contextsolutions.mobileagent.platform.AppBuildConfig
import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import com.contextsolutions.mobileagent.preferences.OllamaConfig
import com.contextsolutions.mobileagent.preferences.OllamaPreferences
import com.contextsolutions.mobileagent.search.SearchCacheDao
import com.contextsolutions.mobileagent.telemetry.TelemetryConsentManager
import com.contextsolutions.mobileagent.telemetry.TelemetryUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the M2 settings screen: Brave key, search toggle, cache clear.
 *
 * The Brave key lives in [SecureStorage] (EncryptedSharedPreferences); this VM
 * never holds the key in memory beyond what the user is currently typing — the
 * UI mask + immediate save-and-discard pattern keeps the key out of the
 * Compose state tree.
 */
class SettingsViewModel(
    private val secureStorage: SecureStorage,
    private val cache: SearchCacheDao,
    private val telemetryConsent: TelemetryConsentManager,
    private val crashReporter: SafeCrashReporter,
    private val languagePreferences: LanguagePreferences,
    private val telemetryUploader: TelemetryUploader,
    private val buildConfig: AppBuildConfig,
    private val memoryStore: MemoryStore,
    private val memoryPreferences: MemoryPreferences,
    private val ollamaPreferences: OllamaPreferences,
    private val ollamaClient: OllamaClient,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // Mirror the consent toggle into UI state. Phase E onboarding's
        // consent screen writes via the same TelemetryConsentManager;
        // observing the flow keeps this Settings surface in sync without
        // a manual refresh on return-to-Settings.
        telemetryConsent.enabledFlow()
            .onEach { enabled -> _state.update { it.copy(telemetryEnabled = enabled) } }
            .launchIn(viewModelScope)
        // PR #10 — mirror the preferred-language preference. Settings is
        // the only surface that writes to this, but the same Flow pattern
        // keeps the UI consistent if a future flow (e.g. an onboarding
        // step) also writes.
        languagePreferences.preferredLanguageFlow()
            .onEach { lang -> _state.update { it.copy(preferredLanguage = lang) } }
            .launchIn(viewModelScope)
        // PR #56 — mirror the Ollama server config so the Settings section
        // reflects an external clear/reload without a manual refresh.
        ollamaPreferences.configFlow()
            .onEach { cfg -> _state.update { it.copy(ollamaConfig = cfg) } }
            .launchIn(viewModelScope)
    }

    fun saveBraveKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            secureStorage.remove(SecureStorageKeys.BRAVE_API_KEY)
        } else {
            secureStorage.put(SecureStorageKeys.BRAVE_API_KEY, trimmed)
        }
        _state.update { it.copy(hasUserKey = trimmed.isNotEmpty(), keyJustSaved = true) }
    }

    fun clearBraveKey() {
        secureStorage.remove(SecureStorageKeys.BRAVE_API_KEY)
        _state.update { it.copy(hasUserKey = false, keyJustSaved = false) }
    }

    fun acknowledgeKeySaved() {
        _state.update { it.copy(keyJustSaved = false) }
    }

    fun saveHfAuthToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            secureStorage.remove(SecureStorageKeys.HF_AUTH_TOKEN)
        } else {
            secureStorage.put(SecureStorageKeys.HF_AUTH_TOKEN, trimmed)
        }
        _state.update {
            it.copy(hasUserHfToken = trimmed.isNotEmpty(), hfTokenJustSaved = true)
        }
    }

    fun clearHfAuthToken() {
        secureStorage.remove(SecureStorageKeys.HF_AUTH_TOKEN)
        _state.update { it.copy(hasUserHfToken = false, hfTokenJustSaved = false) }
    }

    fun acknowledgeHfTokenSaved() {
        _state.update { it.copy(hfTokenJustSaved = false) }
    }

    fun setSearchEnabled(enabled: Boolean) {
        secureStorage.put(SecureStorageKeys.SEARCH_ENABLED, if (enabled) "true" else "false")
        _state.update { it.copy(searchEnabled = enabled) }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            cache.clear()
            val count = cache.count()
            _state.update { it.copy(cacheCount = count, cacheJustCleared = true) }
        }
    }

    fun acknowledgeCacheCleared() {
        _state.update { it.copy(cacheJustCleared = false) }
    }

    fun setTelemetryEnabled(enabled: Boolean) {
        telemetryConsent.setEnabled(enabled)
        // The Application-level Flow observer (MobileAgentApplication) also
        // toggles FirebaseAnalytics.setAnalyticsCollectionEnabled in response.
    }

    /**
     * PR #10 — persist the user's preferred response language. Picked up
     * on the next chat turn (ChatViewModel reads `preferredLanguage()` at
     * send-time); no need to interrupt an in-flight turn.
     */
    fun setPreferredLanguage(language: PreferredLanguage) {
        languagePreferences.setPreferredLanguage(language)
    }

    /**
     * Debug-only — bypass the 24h periodic schedule and fire one telemetry
     * upload immediately. The button on [SettingsScreen] that calls this
     * is gated behind `BuildConfig.DEBUG`; the uploader itself still
     * checks consent, so this never sends data when the user is opted out.
     * Outcome appears in `logcat -s TelemetryWorker:I`.
     */
    fun triggerTelemetryUploadNow() {
        viewModelScope.launch(Dispatchers.IO) { telemetryUploader.upload() }
    }

    /**
     * Re-load the small memory summary shown on the "Memory" row (count +
     * creation-toggle state). Called from the screen's entry effect — this
     * replaces the cross-VM dependency on `MemoryViewModel` that the screen
     * carried before it moved into shared `:ui` (Phase 9).
     */
    fun refreshMemorySummary() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { memoryStore.listAll().size }
            _state.update {
                it.copy(
                    memoryCount = count,
                    memoryCreationEnabled = memoryPreferences.creationEnabled(),
                )
            }
        }
    }

    /**
     * Debug-only — record a non-fatal exception that contains a leak
     * marker string in its message. Verifies that
     * [SafeCrashReporter.recordException] runs the throwable through
     * [com.contextsolutions.mobileagent.observability.ContentRedactor]
     * before forwarding to Crashlytics. The leaked Crashlytics dashboard
     * entry should show the redacted form (`Bearer <redacted>`), NOT
     * the raw token. The user must be opted in for this to surface;
     * Crashlytics collection is gated by the consent toggle.
     */
    fun triggerCrashRedactionTest() {
        crashReporter.recordException(
            RuntimeException(
                "telemetry leak test — Authorization: Bearer test_secret_12345 should be redacted",
            ),
        )
        // Force-flush so the report ships immediately. Without this,
        // Crashlytics queues non-fatals until the next app launch —
        // nothing appears in the dashboard for the developer's
        // verification flow.
        crashReporter.flushPending()
    }

    /**
     * Debug-only — record a breadcrumb that contains a leak marker.
     * Same redaction guarantee as [triggerCrashRedactionTest]: the
     * breadcrumb should appear scrubbed in the dashboard.
     *
     * Note: breadcrumbs ([SafeCrashReporter.log]) only appear in the
     * dashboard ATTACHED TO a recorded exception. To verify breadcrumb
     * redaction end-to-end, tap this button then immediately tap "Test
     * crash redaction" — the breadcrumb will appear in that crash's
     * Logs tab.
     */
    fun triggerBreadcrumbRedactionTest() {
        crashReporter.log(
            "breadcrumb leak test — X-Subscription-Token: BSA-test-key-12345 should be redacted",
        )
    }

    /**
     * PR #56 — probe an Ollama server at [host]:[port] and populate the model
     * dropdowns. Drives the "Test connection" button; an empty result (or any
     * error) reads as Failed. Does not persist anything — the user picks models
     * then taps Save.
     */
    fun testOllama(host: String, port: String) {
        val baseUrl = OllamaConfig(host = host.trim(), port = port.trim().toIntOrNull()).baseUrl()
        if (baseUrl == null) {
            _state.update { it.copy(ollamaTestStatus = OllamaTestStatus.Failed, ollamaModels = emptyList()) }
            return
        }
        _state.update { it.copy(ollamaTestStatus = OllamaTestStatus.Testing) }
        viewModelScope.launch {
            val models = withContext(Dispatchers.IO) { ollamaClient.listModels(baseUrl) }
            _state.update {
                it.copy(
                    ollamaModels = models,
                    ollamaTestStatus = if (models.isEmpty()) OllamaTestStatus.Failed else OllamaTestStatus.Connected,
                )
            }
        }
    }

    /**
     * PR #56 — persist the remote Ollama server + selected models. Once
     * [OllamaConfig.isConfigured], the routing engine serves chat from this
     * server instead of the on-device model. A blank [visionModel] means image
     * turns reuse [chatModel].
     */
    fun saveOllama(host: String, port: String, chatModel: String, visionModel: String) {
        val config = OllamaConfig(
            host = host.trim(),
            port = port.trim().toIntOrNull(),
            chatModel = chatModel.trim(),
            visionModel = visionModel.trim(),
        )
        ollamaPreferences.setConfig(config)
        _state.update { it.copy(ollamaConfig = config, ollamaJustSaved = true) }
    }

    /** PR #56 — clear the remote server (reverts chat to the on-device model). */
    fun clearOllama() {
        ollamaPreferences.clear()
        _state.update {
            it.copy(
                ollamaConfig = OllamaConfig.EMPTY,
                ollamaModels = emptyList(),
                ollamaTestStatus = OllamaTestStatus.Idle,
            )
        }
    }

    fun acknowledgeOllamaSaved() {
        _state.update { it.copy(ollamaJustSaved = false) }
    }

    private fun initialState(): SettingsUiState {
        val hasUser = secureStorage.contains(SecureStorageKeys.BRAVE_API_KEY) &&
            !secureStorage.get(SecureStorageKeys.BRAVE_API_KEY).isNullOrBlank()
        val hasUserHf = secureStorage.contains(SecureStorageKeys.HF_AUTH_TOKEN) &&
            !secureStorage.get(SecureStorageKeys.HF_AUTH_TOKEN).isNullOrBlank()
        val searchEnabled = secureStorage.get(SecureStorageKeys.SEARCH_ENABLED) != "false"
        return SettingsUiState(
            hasUserKey = hasUser,
            hasDevKey = buildConfig.hasBraveDevKey,
            hasUserHfToken = hasUserHf,
            hasDevHfToken = buildConfig.hasHfDevToken,
            searchEnabled = searchEnabled,
            cacheCount = -1L,
            telemetryEnabled = telemetryConsent.enabled(),
            preferredLanguage = languagePreferences.preferredLanguage(),
            isDebugBuild = buildConfig.isDebug,
            memoryCreationEnabled = memoryPreferences.creationEnabled(),
            ollamaConfig = ollamaPreferences.config(),
        ).also {
            // Load cache count off the main thread.
            viewModelScope.launch(Dispatchers.IO) {
                val count = withContext(Dispatchers.IO) { cache.count() }
                _state.update { st -> st.copy(cacheCount = count) }
            }
        }
    }
}

data class SettingsUiState(
    val hasUserKey: Boolean,
    val hasDevKey: Boolean,
    val hasUserHfToken: Boolean,
    val hasDevHfToken: Boolean,
    val searchEnabled: Boolean,
    /** -1 = not yet loaded. */
    val cacheCount: Long,
    val telemetryEnabled: Boolean = false,
    val preferredLanguage: PreferredLanguage = PreferredLanguage.DEFAULT,
    val keyJustSaved: Boolean = false,
    val hfTokenJustSaved: Boolean = false,
    val cacheJustCleared: Boolean = false,
    /** True for a debuggable build — gates debug-only affordances on the screen. */
    val isDebugBuild: Boolean = false,
    /** Memory summary shown on the "Memory" row. -1 = not yet loaded. */
    val memoryCount: Int = -1,
    val memoryCreationEnabled: Boolean = false,
    /** PR #56 — remote Ollama server config (EMPTY = use the on-device model). */
    val ollamaConfig: OllamaConfig = OllamaConfig.EMPTY,
    /** Models discovered by the last "Test connection" (drives the dropdowns). */
    val ollamaModels: List<OllamaModel> = emptyList(),
    val ollamaTestStatus: OllamaTestStatus = OllamaTestStatus.Idle,
    val ollamaJustSaved: Boolean = false,
)

/** Outcome of the Settings "Test connection" probe against an Ollama server. */
enum class OllamaTestStatus { Idle, Testing, Connected, Failed }
