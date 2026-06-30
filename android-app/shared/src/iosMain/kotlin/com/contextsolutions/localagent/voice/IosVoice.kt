package com.contextsolutions.localagent.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * iOS no-op [ChatSpeaker] (PR #41). Read-aloud is deferred on iOS this milestone;
 * an `AVSpeechSynthesizer`-backed speaker is a follow-up. Never reports speaking,
 * so dictation echo-suppression logic stays inert.
 */
class NoOpChatSpeaker : ChatSpeaker {
    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking
    override fun speak(text: String) {}
    override fun stop() {}
}

/**
 * iOS no-op [Dictation] (PR #41). Speech-to-text is deferred this milestone; an
 * `SFSpeechRecognizer`-backed dictation is a follow-up. Never listens / emits.
 */
class NoOpDictation : Dictation {
    override val results: Flow<String> = emptyFlow()
    override val partials: Flow<String> = emptyFlow()
    override val isListening: StateFlow<Boolean> = MutableStateFlow(false)
    override fun start() {}
    override fun stop() {}
    override fun destroy() {}
}
