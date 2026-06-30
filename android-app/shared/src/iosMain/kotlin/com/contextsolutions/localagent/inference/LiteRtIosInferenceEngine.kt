package com.contextsolutions.localagent.inference

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.Clock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS [InferenceEngine] (PR #41) — adapts the Swift [NativeLlmBridge] (callback-shaped,
 * LiteRT-LM Swift + Metal) to the commonMain seam used by the agent loop. The
 * counterpart of `LiteRtInferenceEngine` (Android JNI) / `LlamaServerInferenceEngine`
 * (desktop subprocess); it is the `local` engine inside `RoutingInferenceEngine` on iOS.
 *
 * Tool-calling is not wired this milestone (the bridge has no tool channel — search is
 * no-op on iOS), so [toolDispatcher] is ignored; turns stream text only.
 */
class LiteRtIosInferenceEngine(
    private val bridge: NativeLlmBridge,
    private val enableVision: Boolean = true,
) : InferenceEngine {

    override suspend fun loadModel(modelPath: String, config: InferenceConfig): ModelHandle =
        suspendCancellableCoroutine { cont ->
            val useGpu = config.accelerator == Accelerator.AUTO || config.accelerator == Accelerator.GPU
            bridge.load(
                modelPath = modelPath,
                useGpu = useGpu,
                enableVision = enableVision && config.enableVision,
                onLoaded = { accel ->
                    if (cont.isActive) {
                        cont.resume(
                            IosModelHandle(
                                modelId = modelPath.substringAfterLast('/'),
                                loadedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                                activeAccelerator = if (accel.equals("gpu", ignoreCase = true)) {
                                    Accelerator.GPU
                                } else {
                                    Accelerator.CPU
                                },
                            ),
                        )
                    }
                },
                onError = { msg ->
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(msg))
                },
            )
        }

    override fun unload(handle: ModelHandle) {
        bridge.unload()
    }

    override fun generate(
        handle: ModelHandle,
        request: GenerationRequest,
        toolDispatcher: ToolDispatcher?,
    ): Flow<GenerationEvent> = callbackFlow {
        var tokenIndex = 0
        val nativeHandle = bridge.generate(
            systemInstruction = request.systemInstruction,
            turns = request.toNativeTurns(),
            imageBytes = request.history.lastOrNull()?.imageBytes,
            onToken = { text ->
                trySend(GenerationEvent.TokenChunk(text, tokenIndex++))
            },
            onDone = { reason ->
                trySend(GenerationEvent.Done(totalTokens = tokenIndex, finishReason = reason.toFinishReason()))
                close()
            },
            onError = { msg ->
                trySend(GenerationEvent.Error(msg))
                close()
            },
        )
        awaitClose { nativeHandle.cancel() }
    }
}

/** Map [GenerationRequest] history (or the legacy [GenerationRequest.prompt]) to bridge turns. */
private fun GenerationRequest.toNativeTurns(): List<NativeChatTurn> {
    if (history.isEmpty()) {
        return if (prompt.isNotBlank()) listOf(NativeChatTurn("user", prompt)) else emptyList()
    }
    return history.mapNotNull { msg ->
        val role = when (msg.role) {
            HistoryRole.USER -> "user"
            HistoryRole.MODEL -> "model"
            HistoryRole.SYSTEM -> "system"
            // No tool-calling on iOS this milestone — fold any tool turn in as model text.
            HistoryRole.TOOL -> "model"
        }
        if (msg.text.isBlank() && msg.imageBytes == null) null else NativeChatTurn(role, msg.text)
    }
}

private fun String.toFinishReason(): FinishReason = when (lowercase()) {
    "length", "max_tokens" -> FinishReason.MAX_TOKENS
    "cancelled", "canceled" -> FinishReason.CANCELLED
    "stop", "stop_sequence" -> FinishReason.STOP_SEQUENCE
    else -> FinishReason.END_OF_TURN
}

private class IosModelHandle(
    override val modelId: String,
    override val loadedAtEpochMs: Long,
    override val activeAccelerator: Accelerator,
) : ModelHandle
