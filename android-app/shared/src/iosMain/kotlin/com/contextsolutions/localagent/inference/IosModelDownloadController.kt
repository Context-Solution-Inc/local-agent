package com.contextsolutions.localagent.inference

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** First-run model-download state for the iOS download gate (PR #41). */
sealed interface IosDownloadState {
    data object Idle : IosDownloadState
    data class Downloading(val fraction: Float) : IosDownloadState
    data object Done : IosDownloadState
    data class Failed(val message: String) : IosDownloadState
}

/**
 * Drives the first-run Gemma download on iOS (PR #41) and exposes the
 * model-present gate the Compose entry point feeds to `AppNavHost(modelPresent =)`.
 * Bound as a singleton in `iosModule`; the iOS download screen observes [state] and
 * calls [start]/[retry]. Uses a fresh Ktor/Darwin client per attempt (closed after).
 */
class IosModelDownloadController(
    private val store: IosModelStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<IosDownloadState>(
        if (store.isPresent()) IosDownloadState.Done else IosDownloadState.Idle,
    )
    val state: StateFlow<IosDownloadState> = _state.asStateFlow()

    private val _present = MutableStateFlow(store.isPresent())
    val modelPresent: StateFlow<Boolean> = _present.asStateFlow()

    fun modelPath(): String = store.modelPath()

    /** Begin (or resume) the download. No-op while already downloading or done. */
    fun start() {
        if (_state.value is IosDownloadState.Downloading || _state.value is IosDownloadState.Done) return
        scope.launch {
            val initial = store.downloadedBytes().toFloat() / IosModelSpec.SIZE_BYTES
            _state.value = IosDownloadState.Downloading(initial.coerceIn(0f, 1f))
            // A dedicated Darwin client with NO request timeout — the shared
            // HttpEngineFactory caps requests at 10s, which would abort a multi-GB fetch.
            val client = HttpClient(Darwin)
            try {
                store.ensure(client) { f -> _state.value = IosDownloadState.Downloading(f) }
                _present.value = true
                _state.value = IosDownloadState.Done
            } catch (t: Throwable) {
                _state.value = IosDownloadState.Failed(t.message ?: "download failed")
            } finally {
                client.close()
            }
        }
    }

    fun retry() {
        if (_state.value is IosDownloadState.Failed) {
            _state.value = IosDownloadState.Idle
            start()
        }
    }
}
