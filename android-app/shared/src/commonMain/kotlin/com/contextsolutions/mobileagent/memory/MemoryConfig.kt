package com.contextsolutions.mobileagent.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versioned, JSON-serializable bundle of memory-extraction tunable
 * parameters. Shipped at `:androidApp/src/main/assets/memory_config.json`,
 * parsed at app start. Mirrors `PreflightConfig` in shape so post-launch
 * telemetry-driven tuning can ship via app update without code change.
 */
@Serializable
data class MemoryConfig(
    @SerialName("model_version")
    val modelVersion: String,

    @SerialName("thresholds")
    val thresholds: MemoryThresholds,
) {
    companion object {
        val DEFAULT: MemoryConfig = MemoryConfig(
            modelVersion = "preflight_memory_shared_v1.0.0",
            thresholds = MemoryThresholds.DEFAULT,
        )
    }
}

/**
 * Two-band routing thresholds for `p_has_extraction`
 * (= softmax(presenceLogits) at
 * [com.contextsolutions.mobileagent.classifier.ClassifierOutput.PRESENCE_INDEX_HAS_EXTRACTION]):
 *
 *  - `>= ask` → surface a Save / Dismiss card to the user
 *  - otherwise → silent skip
 *
 * Pre-PR#7 a third "high band" auto-saved at `>= autoSave`. Removed so
 * every classifier-driven save passes through explicit user consent —
 * Lawrence's UX call: the model is not confident enough at v1.0 to
 * justify silent writes, and the prompt card is cheap. Explicit
 * `RememberForgetDetector.Command.Remember` still auto-saves (the user
 * literally typed "remember …").
 *
 * [category] is the existing multi-label sigmoid cutoff used to decide
 * which `MemoryCategory` heads are active for a given turn.
 */
@Serializable
data class MemoryThresholds(
    @SerialName("ask")
    val ask: Float,

    @SerialName("category")
    val category: Float,
) {
    init {
        require(ask in 0f..1f) { "ask must be in [0, 1], was $ask" }
        require(category in 0f..1f) { "category must be in [0, 1], was $category" }
    }

    companion object {
        /** v1.0 ship defaults — `ask` matches PRD §3.2.1's low band; no auto-save band post-PR#7. */
        val DEFAULT: MemoryThresholds = MemoryThresholds(
            ask = 0.15f,
            category = 0.5f,
        )
    }
}
