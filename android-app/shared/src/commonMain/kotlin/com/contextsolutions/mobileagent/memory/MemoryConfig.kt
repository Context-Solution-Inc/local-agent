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
 * Three-band routing thresholds for `p_has_extraction`
 * (= softmax(presenceLogits) at
 * [com.contextsolutions.mobileagent.classifier.ClassifierOutput.PRESENCE_INDEX_HAS_EXTRACTION]):
 *
 *  - `>= autoSave` → save the memory automatically (no user interaction)
 *  - `>= ask`      → surface a Save / Dismiss card to the user
 *  - otherwise     → silent skip
 *
 * [category] is the existing multi-label sigmoid cutoff used to decide
 * which `MemoryCategory` heads are active for a given turn.
 */
@Serializable
data class MemoryThresholds(
    @SerialName("auto_save")
    val autoSave: Float,

    @SerialName("ask")
    val ask: Float,

    @SerialName("category")
    val category: Float,
) {
    init {
        require(autoSave in 0f..1f) { "autoSave must be in [0, 1], was $autoSave" }
        require(ask in 0f..1f) { "ask must be in [0, 1], was $ask" }
        require(category in 0f..1f) { "category must be in [0, 1], was $category" }
        require(ask < autoSave) {
            "ask ($ask) must be strictly less than autoSave ($autoSave)"
        }
    }

    companion object {
        /** v1.0 ship defaults — match the pre-flight 0.85 / 0.15 bands per PRD §3.2.1. */
        val DEFAULT: MemoryThresholds = MemoryThresholds(
            autoSave = 0.85f,
            ask = 0.15f,
            category = 0.5f,
        )
    }
}
