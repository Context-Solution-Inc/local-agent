package com.contextsolutions.localagent.voice

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.darwin.NSObject

/**
 * iOS [ChatSpeaker] actual — reads finalized assistant answers aloud via
 * `AVSpeechSynthesizer` (on-device, no permission, no network), the counterpart of
 * `AndroidTtsSpeaker` (Android `TextToSpeech`) and `DesktopTtsSpeaker` (invariant #42).
 *
 * [isSpeaking] is driven by an [AVSpeechSynthesizerDelegateProtocol] so continuous
 * dictation's echo suppression (#42, in the shared Chat screen) sees the assistant's
 * own voice. [speak] flushes any in-progress utterance first (the `QUEUE_FLUSH`
 * analog); [stop] silences immediately (`AVSpeechBoundaryImmediate`).
 *
 * The synthesizer uses the application audio session by default, so it shares the
 * `.playAndRecord` session [IosSpeechDictation] configures — read-aloud and the
 * always-listening mic coexist.
 */
class IosChatSpeaker : ChatSpeaker {

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val delegate = SpeechDelegate(
        onSpeaking = { _isSpeaking.value = it },
    )
    private val synthesizer = AVSpeechSynthesizer().apply { setDelegate(delegate) }

    // Resolve the device's current language once; the OS voice settings pick the voice.
    private val voice: AVSpeechSynthesisVoice? =
        AVSpeechSynthesisVoice.voiceWithLanguage(AVSpeechSynthesisVoice.currentLanguageCode())

    override fun speak(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // Flush any in-progress utterance so the newest answer wins (QUEUE_FLUSH analog).
        if (synthesizer.speaking) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        val utterance = AVSpeechUtterance.speechUtteranceWithString(trimmed).apply {
            voice?.let { setVoice(it) }
        }
        synthesizer.speakUtterance(utterance)
    }

    override fun stop() {
        if (synthesizer.speaking) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        _isSpeaking.value = false
    }
}

/**
 * Bridges `AVSpeechSynthesizer` lifecycle callbacks to the [ChatSpeaker.isSpeaking]
 * flow. Held strongly by [IosChatSpeaker] since `AVSpeechSynthesizer.delegate` is weak.
 */
private class SpeechDelegate(
    private val onSpeaking: (Boolean) -> Unit,
) : NSObject(), AVSpeechSynthesizerDelegateProtocol {

    @ObjCSignatureOverride
    override fun speechSynthesizer(
        synthesizer: AVSpeechSynthesizer,
        didStartSpeechUtterance: AVSpeechUtterance,
    ) {
        onSpeaking(true)
    }

    @ObjCSignatureOverride
    override fun speechSynthesizer(
        synthesizer: AVSpeechSynthesizer,
        didFinishSpeechUtterance: AVSpeechUtterance,
    ) {
        onSpeaking(false)
    }

    @ObjCSignatureOverride
    override fun speechSynthesizer(
        synthesizer: AVSpeechSynthesizer,
        didCancelSpeechUtterance: AVSpeechUtterance,
    ) {
        onSpeaking(false)
    }
}
