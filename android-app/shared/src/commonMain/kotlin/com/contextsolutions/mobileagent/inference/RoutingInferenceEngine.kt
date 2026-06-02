package com.contextsolutions.mobileagent.inference

import com.contextsolutions.mobileagent.preferences.OllamaPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * [InferenceEngine] that routes the large chat LLM to a remote Ollama server when
 * the user has configured one ([OllamaPreferences]), otherwise to the on-device
 * engine ([local], LiteRT-LM on Android / llama-server on desktop). PR #56.
 *
 * This sits at the [InferenceEngine] seam — below the Android
 * `InferenceSessionManager` and the desktop warm-model runtime — so BOTH
 * platforms get remote routing without touching their lifecycle/session layers
 * or the agent loop. It becomes the value of `single<InferenceEngine>` on each
 * platform, wrapping that platform's existing local engine.
 *
 * The backend is decided once per [loadModel], for the whole resident period:
 *  - Ollama configured **and reachable** → the Ollama engine.
 *  - Ollama configured but **unreachable** → fall back to [local] (user-chosen
 *    behavior: chat keeps working on-device rather than erroring).
 *  - Ollama unconfigured → [local].
 *
 * Editing the server in Settings must drop a resident model so the next turn
 * re-decides — the Android session manager / desktop runtime observe
 * [OllamaPreferences.configFlow] and force an unload (see PR #56 wiring). Absent
 * that hook, a change still takes effect after the 5-min idle unload or restart.
 */
class RoutingInferenceEngine(
    private val local: InferenceEngine,
    /** The remote engine — an [OllamaInferenceEngine] in production; typed as the
     *  seam so the fallback matrix is unit-testable with a fake. */
    private val ollama: InferenceEngine,
    private val preferences: OllamaPreferences,
    private val logger: (String) -> Unit = {},
) : InferenceEngine {

    private enum class Backend { LOCAL, OLLAMA }

    private class RoutedHandle(
        val backend: Backend,
        val delegate: ModelHandle,
    ) : ModelHandle by delegate

    override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle {
        if (preferences.config().isConfigured) {
            try {
                val handle = ollama.loadModel(modelPath, config)
                logger("routing → Ollama (${handle.modelId})")
                return RoutedHandle(Backend.OLLAMA, handle)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                logger("Ollama unavailable (${t.message}); falling back to on-device model")
            }
        }
        return RoutedHandle(Backend.LOCAL, local.loadModel(modelPath, config))
    }

    override fun unload(handle: ModelHandle) {
        val routed = handle as? RoutedHandle ?: return local.unload(handle)
        when (routed.backend) {
            Backend.LOCAL -> local.unload(routed.delegate)
            Backend.OLLAMA -> ollama.unload(routed.delegate)
        }
    }

    override fun generate(
        handle: ModelHandle,
        request: GenerationRequest,
        toolDispatcher: ToolDispatcher?,
    ): Flow<GenerationEvent> {
        val routed = handle as? RoutedHandle
            ?: return local.generate(handle, request, toolDispatcher)
        return when (routed.backend) {
            Backend.LOCAL -> local.generate(routed.delegate, request, toolDispatcher)
            Backend.OLLAMA -> ollama.generate(routed.delegate, request, toolDispatcher)
        }
    }
}
