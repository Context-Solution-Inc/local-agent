package com.contextsolutions.mobileagent.app.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads finalized assistant answers aloud when the user enables the speaker
 * toggle. Backed by Android's on-device [TextToSpeech] engine — no permission,
 * no network — consistent with the app's offline/privacy posture (Gemma E2B is
 * text+vision only, so there is no LLM path for speech anyway).
 *
 * An interface so [ChatViewModel] can be unit-tested with a fake: the real
 * engine needs a live Android TTS service that isn't available under plain JVM
 * tests.
 */
interface ChatSpeaker {
    /** Speak [text], flushing any in-progress utterance. No-op on blank text. */
    fun speak(text: String)

    /** Stop any in-progress speech immediately. */
    fun stop()

    /**
     * `true` while an utterance is actively playing. Continuous dictation
     * watches this to pause the microphone during playback so the recognizer
     * never transcribes the assistant's own voice (echo).
     */
    val isSpeaking: StateFlow<Boolean>
}

/**
 * [TextToSpeech]-backed [ChatSpeaker]. Provided as an application-scoped Hilt
 * singleton so one engine serves the whole app and survives ViewModel
 * recreation. Engine init is asynchronous; a [speak] that lands before init
 * completes is held as [pendingText] and flushed from the init callback.
 */
class AndroidTtsSpeaker(context: Context) : ChatSpeaker {

    private var ready = false
    private var pendingText: String? = null

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
                override fun onDone(utteranceId: String?) { _isSpeaking.value = false }
                @Deprecated("legacy signature, still required by the abstract class")
                override fun onError(utteranceId: String?) { _isSpeaking.value = false }
            })
            ready = true
            pendingText?.let { enqueue(it) }
            pendingText = null
        } else {
            Log.w(TAG, "TextToSpeech init failed: status=$status")
        }
    }

    override fun speak(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (ready) enqueue(trimmed) else pendingText = trimmed
    }

    override fun stop() {
        pendingText = null
        if (ready) tts.stop()
        _isSpeaking.value = false
    }

    private fun enqueue(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private companion object {
        const val TAG = "ChatSpeaker"
        const val UTTERANCE_ID = "assistant-response"
    }
}
