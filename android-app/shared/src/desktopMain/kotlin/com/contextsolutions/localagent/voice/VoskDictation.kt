package com.contextsolutions.localagent.voice

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.contextsolutions.localagent.platform.DesktopDiag
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Desktop [Dictation] (docs/DESKTOP_PORT_PLAN.md, Phase 7) — offline STT via Vosk
 * (JNI), the desktop counterpart of Android's `SpeechRecognizer`-backed
 * `SpeechDictation`. Fully offline, no network.
 *
 * Captures 16 kHz mono PCM from the default microphone (`javax.sound.sampled`)
 * and feeds it to a Vosk [Recognizer]; each completed utterance's text is emitted
 * on [results]. Continuous by construction (the capture loop runs until [stop]),
 * so there's no Android-style single-shot restart dance.
 *
 * The acoustic model (~40 MB) is acquired through [modelProvider] — by default
 * [VoskModelStore.ensure], which downloads + caches it under `<app-data>/models/vosk`
 * on first use (env override + manual drop still honoured). Acquisition is async, so
 * [start] launches a job that resolves the model (downloading if needed) and only then
 * opens the mic; [isListening] flips true once capture actually begins.
 *
 * **Degrades to no-op** when the model can't be obtained (offline first run, no disk)
 * or no microphone line is available — [start]'s job logs and returns, matching the
 * ONNX/GGUF "missing artifact ⇒ silent disable" pattern, so a headless CI box neither
 * errors nor blocks.
 *
 * **Suspend/resume recovery (debug PR).** When the laptop suspends, the OS reclaims the
 * `TargetDataLine`; on resume the line is stale and `read()` returns -1 / a run of 0s, or
 * blocks indefinitely. Previously the read loop `continue`d on `read <= 0` forever — Vosk
 * got no audio, yet [isListening] stayed true (mic looked active). Now the capture is split
 * into per-session attempts: a session ends [SessionEnd.Stale] on a stale read (see
 * [classifyRead]) or when the [stallWatchdog] force-closes a silent line, and the outer loop
 * reopens the mic (keeping the [Model]/[Recognizer] alive) with bounded backoff. The
 * watchdog is the catch-all for the "read blocks forever" case the read-value checks can't
 * see — closing the line from another thread unblocks the read.
 */
class VoskDictation(
    private val modelProvider: suspend () -> String? = { VoskModelStore().ensure() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val logger: (String) -> Unit = { System.err.println("[Dictation] $it") },
) : Dictation {

    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val results: Flow<String> = _results.asSharedFlow()

    private val _partials = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val partials: Flow<String> = _partials.asSharedFlow()

    private val _isListening = MutableStateFlow(false)
    override val isListening = _isListening.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var job: Job? = null

    @Volatile
    private var watchdog: Job? = null

    /**
     * The live capture line. Held at class scope (not loop-local) so the [stallWatchdog]
     * can `close()` it from another coroutine to break a wedged [TargetDataLine.read].
     */
    @Volatile
    private var line: TargetDataLine? = null

    /** Wall-clock of the last frame that actually carried audio; drives the watchdog. */
    @Volatile
    private var lastAudioAtMs: Long = 0L

    override fun start() {
        if (job?.isActive == true) return
        lastAudioAtMs = System.currentTimeMillis()
        watchdog = scope.launch { stallWatchdog() }
        job = scope.launch {
            try {
                val modelPath = modelProvider()
                if (modelPath == null) {
                    logger("no Vosk model and it couldn't be downloaded — dictation disabled (check the network, or set LOCALAGENT_VOSK_MODEL)")
                    return@launch
                }
                captureLoop(modelPath)
            } finally {
                watchdog?.cancel()
                watchdog = null
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        watchdog?.cancel()
        watchdog = null
        // Closing the line unblocks any in-flight read so the session coroutine unwinds.
        line?.let { runCatching { it.stop(); it.close() } }
        line = null
        _isListening.value = false
    }

    override fun destroy() = stop()

    /**
     * Owns the [Model]/[Recognizer] (alive across reopens) and the reopen-with-backoff loop.
     * Each [runCaptureSession] opens one mic line and reads until it ends; a [SessionEnd.Stale]
     * end triggers recovery, [SessionEnd.Cancelled] stops.
     */
    private suspend fun captureLoop(modelPath: String) {
        val format = AudioFormat(SAMPLE_RATE, 16, 1, true, false) // 16kHz, 16-bit, mono, signed, little-endian
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            logger("no microphone line available — dictation disabled")
            return
        }
        try {
            // Silence Vosk's native model-load LOG spam in production; keep it on a
            // debug/internal run (`-Dlocalagent.debug=true`). The native lib logs to
            // stderr directly, so this is the only lever (DesktopDiag can't gate it).
            LibVosk.setLogLevel(if (DesktopDiag.verbose) LogLevel.INFO else LogLevel.WARNINGS)
            Model(modelPath).use { model ->
                Recognizer(model, SAMPLE_RATE).use { recognizer ->
                    var consecutiveOpenFailures = 0
                    while (coroutineContext.isActive) {
                        val end = runCaptureSession(info, format, recognizer)
                        if (end == SessionEnd.Cancelled || !coroutineContext.isActive) break
                        // Stale: the line died (suspend/resume) or the watchdog force-closed it.
                        // A session that opened then went stale is a normal recovery (reset the
                        // counter); one that couldn't even open counts toward the give-up guard
                        // so a permanently-gone device doesn't spin forever.
                        consecutiveOpenFailures = if (lastSessionFailedToOpen) consecutiveOpenFailures + 1 else 0
                        if (consecutiveOpenFailures >= MAX_REOPEN_FAILURES) {
                            logger("microphone unavailable after $consecutiveOpenFailures recovery attempts — dictation disabled")
                            break
                        }
                        val backoff = backoffMs(consecutiveOpenFailures)
                        logger("microphone line went stale — reopening in ${backoff}ms")
                        delay(backoff)
                        recognizer.reset()
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            logger("Vosk capture failed: ${t.message}")
        } finally {
            line?.let { runCatching { it.stop(); it.close() } }
            line = null
            _isListening.value = false
        }
    }

    /** Set when the most recent [runCaptureSession] could not even open the line. */
    @Volatile
    private var lastSessionFailedToOpen = false

    /**
     * Opens one mic line and reads until the coroutine is cancelled ([SessionEnd.Cancelled])
     * or the line goes stale ([SessionEnd.Stale]). The line is closed before returning so the
     * outer loop can cleanly reopen.
     */
    private suspend fun runCaptureSession(
        info: DataLine.Info,
        format: AudioFormat,
        recognizer: Recognizer,
    ): SessionEnd {
        lastSessionFailedToOpen = false
        val local: TargetDataLine = try {
            (AudioSystem.getLine(info) as TargetDataLine).apply {
                open(format)
                start()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            lastSessionFailedToOpen = true
            line = null
            _isListening.value = false
            logger("failed to open microphone line: ${t.message}")
            return SessionEnd.Stale
        }
        line = local
        lastAudioAtMs = System.currentTimeMillis()
        _isListening.value = true
        DesktopDiag.log("[Dictation] microphone open (${format.sampleRate.toInt()}Hz, buffer ${BUFFER_BYTES}B)")
        logger("microphone open — dictation listening")
        val buffer = ByteArray(BUFFER_BYTES)
        var lastPartial = ""
        var consecutiveZero = 0
        try {
            while (coroutineContext.isActive) {
                val read = try {
                    local.read(buffer, 0, buffer.size)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // A wedged line (incl. the watchdog's close()) surfaces here.
                    logger("microphone read threw (${t.message}) — treating line as stale")
                    return SessionEnd.Stale
                }
                when (classifyRead(read, consecutiveZero)) {
                    ReadOutcome.Stale -> {
                        logger("microphone read returned $read (consecutiveZero=$consecutiveZero) — treating line as stale")
                        return SessionEnd.Stale
                    }
                    ReadOutcome.KeepWaiting -> {
                        consecutiveZero++
                        DesktopDiag.log("[Dictation] empty read ($consecutiveZero/$MAX_CONSECUTIVE_ZERO)")
                        // Avoid a hot spin while the line is momentarily empty.
                        delay(EMPTY_READ_PAUSE_MS)
                        continue
                    }
                    ReadOutcome.Data -> {
                        consecutiveZero = 0
                        lastAudioAtMs = System.currentTimeMillis()
                    }
                }
                if (recognizer.acceptWaveForm(buffer, read)) {
                    extractText(recognizer.result)?.let { _results.tryEmit(it) }
                    lastPartial = ""
                } else {
                    // PR #67 — stream the in-progress transcript so words appear in the
                    // prompt box while talking. Vosk grows the `partial` field each frame;
                    // emit only on change to avoid spamming identical strings.
                    val partial = extractPartial(recognizer.partialResult)
                    if (partial != null && partial != lastPartial) {
                        lastPartial = partial
                        _partials.tryEmit(partial)
                    }
                }
            }
            return SessionEnd.Cancelled
        } finally {
            // Drop _isListening so the mic button reflects the gap; it flips back true when
            // the next session opens. Don't null `line` if the watchdog already swapped it.
            _isListening.value = false
            runCatching { local.stop(); local.close() }
            if (line === local) line = null
        }
    }

    /**
     * Watchdog for the "read blocks forever" failure mode (Linux suspend/resume can leave
     * [TargetDataLine.read] wedged with no return value). If no audio has arrived for
     * [STALL_TIMEOUT_MS] while we believe we're listening, force-close the line — that
     * unblocks the read, which [runCaptureSession] then classifies as stale and recovers.
     */
    private suspend fun stallWatchdog() {
        while (coroutineContext.isActive) {
            delay(WATCHDOG_TICK_MS)
            if (!_isListening.value) continue
            val idle = System.currentTimeMillis() - lastAudioAtMs
            if (idle > STALL_TIMEOUT_MS) {
                val stale = line
                if (stale != null) {
                    logger("no audio for ${idle}ms — line appears stale, forcing reopen")
                    // Closing from this thread breaks a blocked read() on the capture thread.
                    runCatching { stale.stop(); stale.close() }
                    // Reset so we don't re-fire every tick before the reopen lands.
                    lastAudioAtMs = System.currentTimeMillis()
                }
            }
        }
    }

    private fun extractText(result: String): String? = try {
        json.parseToJsonElement(result).jsonObject["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /** Vosk's `partialResult` carries the live transcript under `"partial"`. */
    private fun extractPartial(result: String): String? = try {
        json.parseToJsonElement(result).jsonObject["partial"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /** Why a single [runCaptureSession] ended. */
    private enum class SessionEnd { Cancelled, Stale }

    /** Classification of one [TargetDataLine.read] return value. */
    internal enum class ReadOutcome { Data, KeepWaiting, Stale }

    companion object {
        private const val SAMPLE_RATE = 16_000f
        private const val BUFFER_BYTES = 4096

        /** A run of empty reads longer than this means the line is dead, not just quiet. */
        internal const val MAX_CONSECUTIVE_ZERO = 100

        private const val EMPTY_READ_PAUSE_MS = 20L
        private const val WATCHDOG_TICK_MS = 1_000L
        private const val STALL_TIMEOUT_MS = 3_000L
        private const val MAX_REOPEN_FAILURES = 5

        /**
         * Pure classification of a [TargetDataLine.read] result — extracted so it can be
         * unit-tested without audio hardware (mirrors the `LinuxNotificationPresenter.buildArgv`
         * pattern). `-1` is EOF/closed (the suspend/resume signature); a long run of `0`s means
         * the line stopped delivering; anything `> 0` is real audio.
         */
        internal fun classifyRead(read: Int, consecutiveZero: Int): ReadOutcome = when {
            read > 0 -> ReadOutcome.Data
            read < 0 -> ReadOutcome.Stale
            consecutiveZero >= MAX_CONSECUTIVE_ZERO -> ReadOutcome.Stale
            else -> ReadOutcome.KeepWaiting
        }

        /** Backoff before a reopen attempt: 300 ms, doubling, capped at 3 s. */
        internal fun backoffMs(consecutiveFailures: Int): Long {
            val base = 300L shl consecutiveFailures.coerceIn(0, 4)
            return base.coerceAtMost(3_000L)
        }
    }
}
