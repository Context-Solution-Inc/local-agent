package com.contextsolutions.mobileagent.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Persistent user preference pointing the *large chat LLM* at a remote
 * [Ollama](https://ollama.com) server on the user's LAN (PR #56). When
 * [OllamaConfig.isConfigured] is true the agent routes chat generation to that
 * server instead of the on-device runtime (LiteRT-LM on Android, llama-server on
 * desktop) — the pre-flight classifier, embedder, search verticals and memory
 * stay fully on-device regardless.
 *
 * Mirrors [com.contextsolutions.mobileagent.language.LanguagePreferences]:
 *
 *  - Plain `SharedPreferences` on Android / a `DesktopJsonStore` file on desktop
 *    (host/port/model are configuration, not credentials).
 *  - In-memory `MutableStateFlow` seeded from disk at construction; writes update
 *    both for next-process recovery and current-process subscribers.
 *  - The whole [OllamaConfig] is stored as one JSON blob under a single key.
 *
 * The [configFlow] is load-bearing: editing the server in Settings must drop a
 * resident model so the next turn re-decides the backend (see
 * `RoutingInferenceEngine`). The Android session manager / desktop warm-model
 * runtime observe it and force an unload on change.
 */
interface OllamaPreferences {

    /** Snapshot read. Safe from any dispatcher; serves from in-memory state. */
    fun config(): OllamaConfig

    /** Reactive read. Emits the current value on subscribe, then each change. */
    fun configFlow(): Flow<OllamaConfig>

    /** Persist [config] for current and future processes. Idempotent. */
    fun setConfig(config: OllamaConfig)

    /** Clear the remote-server config (reverts to the on-device model). */
    fun clear() = setConfig(OllamaConfig.EMPTY)
}

/**
 * Remote Ollama server configuration. [host] is an IP or hostname (no scheme),
 * [port] is Ollama's listen port (default 11434). [chatModel] serves text turns;
 * [visionModel] serves image turns — when blank, image turns fall back to
 * [chatModel] (many Ollama models, e.g. `gemma3:4b`, are multimodal).
 */
@Serializable
data class OllamaConfig(
    val host: String = "",
    val port: Int? = null,
    val chatModel: String = "",
    val visionModel: String = "",
) {
    /** True once a server + chat model are set — the gate that disables the local LLM. */
    val isConfigured: Boolean
        get() = host.isNotBlank() && port != null && chatModel.isNotBlank()

    /**
     * Base URL for both the OpenAI-compatible (`/v1/...`) and native (`/api/...`)
     * endpoints, e.g. `http://192.168.1.50:11434`. Null when host/port are unset.
     * A bare IP/hostname gets an `http://` scheme (local Ollama is plain HTTP);
     * an explicit scheme the user typed is preserved.
     */
    fun baseUrl(): String? {
        if (host.isBlank() || port == null) return null
        val h = host.trim().trimEnd('/')
        val withScheme = if (h.startsWith("http://") || h.startsWith("https://")) h else "http://$h"
        return "$withScheme:$port"
    }

    /** Model to use for a turn, picking [visionModel] for image turns when set. */
    fun modelFor(hasImage: Boolean): String =
        if (hasImage && visionModel.isNotBlank()) visionModel else chatModel

    companion object {
        const val DEFAULT_PORT = 11434
        val EMPTY = OllamaConfig()
    }
}
