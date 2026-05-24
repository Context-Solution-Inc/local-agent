package com.contextsolutions.mobileagent.app.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Continuous in-app dictation built on [SpeechRecognizer]. The recognizer is
 * single-shot — it stops after a speech pause — so "continuous" means
 * restarting it on every result/timeout while [start]ed (the standard pattern).
 * Each finalized utterance is delivered via [onText]; the caller appends it to
 * the input box.
 *
 * Must be driven from the main thread ([SpeechRecognizer] requirement); the
 * Compose caller satisfies this. While the TTS speaker is talking the caller
 * [stop]s us (gated on [ChatSpeaker.isSpeaking]) so we never transcribe the
 * assistant's own playback (echo). Needs the RECORD_AUDIO runtime permission.
 */
class SpeechDictation(
    context: Context,
    private val onText: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wantListening = false
    private var listening = false

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }

    /** Begin (or resume) continuous listening. Safe to call repeatedly. */
    fun start() {
        wantListening = true
        ensureRecognizer()
        beginListening()
    }

    /** Pause listening (e.g. while TTS speaks). Keeps the engine alive. */
    fun stop() {
        wantListening = false
        handler.removeCallbacksAndMessages(null)
        if (listening) runCatching { recognizer?.cancel() }
        listening = false
    }

    /** Tear down the engine entirely. Call from `onDispose`. */
    fun destroy() {
        stop()
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(listener)
        }
    }

    private fun beginListening() {
        if (!wantListening || listening) return
        val r = recognizer ?: return
        listening = true
        runCatching { r.startListening(intent) }
            .onFailure {
                listening = false
                Log.w(TAG, "startListening failed", it)
            }
    }

    /** Schedule a restart after a short delay so rapid cycles don't busy-loop. */
    private fun restartSoon() {
        listening = false
        if (!wantListening) return
        handler.postDelayed({ beginListening() }, RESTART_DELAY_MS)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(onText)
            restartSoon()
        }

        override fun onError(error: Int) {
            // Permission revoked mid-session is genuinely fatal; everything
            // else (no-match, timeout, recognizer-busy) is a normal pause in a
            // continuous loop — restart after the debounce delay.
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                listening = false
                Log.w(TAG, "dictation stopped: insufficient permissions")
                return
            }
            restartSoon()
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private companion object {
        const val TAG = "SpeechDictation"
        const val RESTART_DELAY_MS = 150L
    }
}
