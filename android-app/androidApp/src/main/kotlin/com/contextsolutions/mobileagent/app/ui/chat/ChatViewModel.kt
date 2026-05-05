package com.contextsolutions.mobileagent.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.mobileagent.app.service.InferenceSessionManager
import com.contextsolutions.mobileagent.app.service.ModelInventory
import com.contextsolutions.mobileagent.app.service.SessionState
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single-prompt test surface for M1 WS-1.
 *
 * Not a real chat — no history, no agent loop, no tools. WS-3/WS-11 build the
 * real chat experience on top of [InferenceSessionManager]. This VM exists only
 * to drive end-to-end validation: load → stream → idle-unload → reload.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessionManager: InferenceSessionManager,
    private val inventory: ModelInventory,
) : ViewModel() {

    val sessionState: StateFlow<SessionState> = sessionManager.state

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var currentJob: Job? = null

    fun send(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        currentJob?.cancel()
        _ui.update {
            it.copy(
                lastPrompt = trimmed,
                response = "",
                tokens = 0,
                isGenerating = true,
                finishReason = null,
                error = null,
            )
        }
        currentJob = viewModelScope.launch {
            try {
                sessionManager.generate(
                    modelPath = inventory.localFile().absolutePath,
                    request = GenerationRequest(prompt = trimmed),
                ).collect { event ->
                    when (event) {
                        is GenerationEvent.TokenChunk -> _ui.update {
                            it.copy(
                                response = it.response + event.text,
                                tokens = event.tokenIndex + 1,
                            )
                        }
                        is GenerationEvent.Done -> _ui.update {
                            it.copy(isGenerating = false, finishReason = event.finishReason.name)
                        }
                        is GenerationEvent.Error -> _ui.update {
                            it.copy(isGenerating = false, error = event.message)
                        }
                        // Tool calls aren't wired in M1 — agent loop in WS-3 handles them.
                        is GenerationEvent.FunctionCall -> Unit
                    }
                }
            } catch (e: CancellationException) {
                _ui.update { it.copy(isGenerating = false, finishReason = "CANCELLED") }
                throw e
            } catch (t: Throwable) {
                _ui.update { it.copy(isGenerating = false, error = t.message ?: t::class.simpleName) }
            }
        }
    }

    /** Cancels the in-flight generation, leaving the partial response visible. */
    fun cancel() {
        currentJob?.cancel()
    }

    /**
     * Debug action: drop the resident model immediately. Useful to verify the
     * onTrimMemory path without actually inducing system memory pressure, and
     * to test that the next prompt cold-loads correctly.
     */
    fun forceUnload() {
        sessionManager.forceUnload()
    }
}

data class ChatUiState(
    val lastPrompt: String = "",
    val response: String = "",
    val tokens: Int = 0,
    val isGenerating: Boolean = false,
    val finishReason: String? = null,
    val error: String? = null,
)
