package com.contextsolutions.localagent.inference

/**
 * Swift→Kotlin bridge for the on-device LLM on iOS (PR #41).
 *
 * iOS has no Kotlin/JNI LiteRT-LM runtime (Android) and no llama-server subprocess
 * (desktop). Instead the **Swift** app implements this callback-shaped interface
 * using the official LiteRT-LM Swift package (Metal GPU + CPU fallback, Gemma 4
 * E2B), and registers the instance into Koin via `initKoin(bridge)`. The Kotlin
 * [LiteRtIosInferenceEngine] adapts it to the [InferenceEngine] seam (suspend +
 * `Flow`), so the agent loop / ViewModels are unchanged.
 *
 * Deliberately callback-based (no suspend / `Flow`) so it is trivial to conform to
 * from Swift. Tool-calling is **not** modelled this milestone (search is no-op on
 * iOS, so the agent loop registers no tools) — a follow-up adds an `onToolCall`
 * round-trip.
 */
interface NativeLlmBridge {

    /**
     * Load the model at [modelPath]. [useGpu] requests Metal (the impl falls back to
     * CPU if Metal init fails). [enableVision] turns on the vision tower at engine
     * init. Exactly one of [onLoaded] (with the accelerator actually used —
     * `"gpu"`/`"cpu"`) or [onError] is invoked.
     */
    fun load(
        modelPath: String,
        useGpu: Boolean,
        enableVision: Boolean,
        onLoaded: (accelerator: String) -> Unit,
        onError: (message: String) -> Unit,
    )

    /**
     * Run one user turn. [systemInstruction] + [turns] (the full conversation, last
     * entry = the current user message) are handed to a LiteRT-LM `Conversation` so
     * Gemma's chat template is applied natively. [imageBytes] (a downscaled JPEG) is
     * attached to the current turn when vision is enabled. Streamed text arrives on
     * [onToken]; the turn ends with exactly one [onDone] (finish reason `"stop"`/
     * `"length"`/`"cancelled"`) or [onError]. The returned [NativeGenHandle.cancel]
     * stops an in-flight decode (wired to the chat Cancel button / Flow cancellation).
     */
    fun generate(
        systemInstruction: String?,
        turns: List<NativeChatTurn>,
        imageBytes: ByteArray?,
        onToken: (String) -> Unit,
        onDone: (finishReason: String) -> Unit,
        onError: (message: String) -> Unit,
    ): NativeGenHandle

    /** Release the model + KV cache. Safe to call repeatedly. */
    fun unload()
}

/** One conversation turn passed to the bridge. [role] is `"system"`, `"user"`, or `"model"`. */
data class NativeChatTurn(val role: String, val text: String)

/** Handle to an in-flight [NativeLlmBridge.generate] call. */
interface NativeGenHandle {
    /** Stop the decode; the impl should invoke `onDone("cancelled")` or simply stop emitting. */
    fun cancel()
}
