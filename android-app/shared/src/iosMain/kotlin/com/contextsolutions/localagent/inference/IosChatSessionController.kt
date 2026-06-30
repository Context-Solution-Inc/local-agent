package com.contextsolutions.localagent.inference

import com.contextsolutions.localagent.agent.ChatSessionController
import com.contextsolutions.localagent.agent.InferenceSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * iOS [ChatSessionController] (PR #41) — wraps the [InferenceEngine] (a
 * `RoutingInferenceEngine` over [LiteRtIosInferenceEngine] + Ollama) and keeps the
 * model resident once loaded, like the desktop [DesktopChatSessionController] (no
 * Android foreground service). [state] transitions Unloaded → Loading → Loaded
 * around the first [newSession]; the accelerator comes from the loaded handle (GPU
 * on Metal, CPU fallback, REMOTE when routed to Ollama).
 *
 * Milestone-1 limitation: the resident handle is not dropped on a remote-LLM
 * settings change mid-session (a relaunch re-decides the backend); auto re-decide
 * is a follow-up (mirrors the Android/desktop `configFlow` unload).
 */
class IosChatSessionController(
    private val engine: InferenceEngine,
    private val modelPath: () -> String,
    private val config: InferenceConfig = InferenceConfig(enableVision = true),
) : ChatSessionController {

    private val _state = MutableStateFlow<SessionState>(SessionState.Unloaded)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var handle: ModelHandle? = null

    override suspend fun newSession(): InferenceSession = mutex.withLock {
        val loaded = handle ?: load()
        IosInferenceSession(engine, loaded)
    }

    private suspend fun load(): ModelHandle {
        if (_state.value !is SessionState.Loaded) _state.value = SessionState.Loading
        return try {
            val h = engine.loadModel(modelPath(), config)
            handle = h
            _state.value = SessionState.Loaded(h.activeAccelerator)
            h
        } catch (t: Throwable) {
            _state.value = SessionState.Failed(t.message ?: "model load failed", t)
            throw t
        }
    }
}

private class IosInferenceSession(
    private val engine: InferenceEngine,
    private val handle: ModelHandle,
) : InferenceSession {
    override fun generate(
        request: com.contextsolutions.localagent.inference.GenerationRequest,
        toolDispatcher: com.contextsolutions.localagent.inference.ToolDispatcher?,
    ): Flow<GenerationEvent> = engine.generate(handle, request, toolDispatcher)
}
