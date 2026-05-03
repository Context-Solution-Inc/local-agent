package com.contextsolutions.mobileagent.app.spike

import android.content.Context
import android.os.Build
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import com.contextsolutions.mobileagent.inference.InferenceConfig
import com.contextsolutions.mobileagent.inference.InferenceEngine
import com.contextsolutions.mobileagent.inference.ModelHandle
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Orchestrates an M0 benchmark run end-to-end:
 *
 * 1. Loads the (real or stub) [InferenceEngine] with the configured model + accelerator.
 * 2. Runs each canonical prompt, recording first-token latency, sustained tok/s,
 *    peak RSS, and thermal trajectory.
 * 3. Writes a JSON artifact to the app's internal storage (`spike-results/`) for
 *    pulling off-device with `adb pull`.
 *
 * The runner does NOT decide whether numbers are "good enough" — that judgement happens
 * in `docs/M0_DECISION_MEMO.md`. Its job is to capture clean data.
 */
class SpikeRunner(
    private val context: Context,
    private val engine: InferenceEngine,
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) {

    suspend fun run(
        modelPath: String,
        accelerator: String,
        kvCacheTokens: Int = 8192,
        prompts: List<SpikePrompt> = CanonicalPrompts.ALL,
        onProgress: (SpikeProgress) -> Unit = {},
    ): SpikeRun {
        val thermal = ThermalMonitor(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermal.start()

        val runId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val results = mutableListOf<SpikePromptResult>()
        var coldLoadMs: Long? = null
        var handle: ModelHandle? = null
        try {
            onProgress(SpikeProgress.Loading)
            val loadStart = System.currentTimeMillis()
            handle = engine.loadModel(
                modelPath = modelPath,
                config = InferenceConfig(kvCacheTokens = kvCacheTokens),
            )
            coldLoadMs = System.currentTimeMillis() - loadStart

            for ((index, prompt) in prompts.withIndex()) {
                onProgress(SpikeProgress.Generating(promptIndex = index, total = prompts.size))
                val perPromptColdLoad = if (index == 0) coldLoadMs else null
                results += runPrompt(handle, prompt, perPromptColdLoad, thermal)
            }
        } finally {
            handle?.let { engine.unload(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermal.stop()
        }
        val completedAt = System.currentTimeMillis()
        val run = SpikeRun(
            runId = runId,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            accelerator = accelerator,
            modelPath = modelPath,
            kvCacheTokens = kvCacheTokens,
            startedAtEpochMs = startedAt,
            completedAtEpochMs = completedAt,
            results = results,
        )
        persist(run)
        onProgress(SpikeProgress.Done(run))
        return run
    }

    private suspend fun runPrompt(
        handle: ModelHandle,
        prompt: SpikePrompt,
        coldLoadMs: Long?,
        thermal: ThermalMonitor,
    ): SpikePromptResult {
        val thermalStart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            thermal.currentThermalStatus() else 0
        val genStart = System.currentTimeMillis()
        var firstTokenAt = -1L
        var totalTokens = 0
        var peakRss = MemorySnapshot.rssBytes()
        var peakNative = MemorySnapshot.nativeHeapBytes()
        var error: String? = null
        try {
            engine.generate(handle, GenerationRequest(prompt = prompt.text, maxTokens = prompt.maxTokens))
                .collect { event ->
                    when (event) {
                        is GenerationEvent.TokenChunk -> {
                            if (firstTokenAt < 0) firstTokenAt = System.currentTimeMillis()
                            totalTokens = event.tokenIndex + 1
                            val rss = MemorySnapshot.rssBytes()
                            if (rss > peakRss) peakRss = rss
                            val native = MemorySnapshot.nativeHeapBytes()
                            if (native > peakNative) peakNative = native
                        }
                        is GenerationEvent.FunctionCall -> {
                            // Tool calls are out of scope for the M0 spike; the harness
                            // measures generation throughput, not the agent loop.
                        }
                        is GenerationEvent.Done -> Unit
                        is GenerationEvent.Error -> { error = event.message }
                    }
                }
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName
        }
        val genEnd = System.currentTimeMillis()
        val firstTokenMs = if (firstTokenAt > 0) firstTokenAt - genStart else genEnd - genStart
        val totalMs = genEnd - genStart
        val generationOnlyMs = (totalMs - firstTokenMs).coerceAtLeast(1)
        val tps = if (totalTokens > 1)
            ((totalTokens - 1) * 1000.0) / generationOnlyMs else 0.0
        val thermalEnd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            thermal.currentThermalStatus() else 0

        return SpikePromptResult(
            promptId = prompt.id,
            prompt = prompt.text,
            coldLoadMs = coldLoadMs,
            firstTokenLatencyMs = firstTokenMs,
            totalGenerationMs = totalMs,
            totalTokens = totalTokens,
            sustainedTokensPerSecond = tps,
            peakRssBytes = peakRss,
            peakNativeHeapBytes = peakNative,
            thermalStateAtStart = thermalStart,
            thermalStateAtEnd = thermalEnd,
            thermalStateMaxObserved = thermal.peakObservedThermalStatus(),
            errorMessage = error,
        )
    }

    private fun persist(run: SpikeRun) {
        val outDir = File(context.filesDir, "spike-results").apply { mkdirs() }
        val file = File(outDir, "spike-${run.runId}.json")
        file.writeText(json.encodeToString(run))
    }
}

sealed interface SpikeProgress {
    object Loading : SpikeProgress
    data class Generating(val promptIndex: Int, val total: Int) : SpikeProgress
    data class Done(val run: SpikeRun) : SpikeProgress
}

data class SpikePrompt(
    val id: String,
    val text: String,
    val maxTokens: Int = 512,
)

/**
 * Canonical prompt set for the M0 spike. Mix of short and long generations across
 * categories that exercise different KV-cache pressure profiles.
 *
 * KEEP THIS LIST STABLE — comparisons across runs (different accelerators, model
 * variants, OS versions) are only meaningful when prompts are held constant.
 */
object CanonicalPrompts {
    val ALL: List<SpikePrompt> = listOf(
        SpikePrompt(
            id = "short_factual",
            text = "What is the capital of France?",
            maxTokens = 64,
        ),
        SpikePrompt(
            id = "medium_explanation",
            text = "Explain how DNS resolves a domain name to an IP address. Keep it under 150 words.",
            maxTokens = 256,
        ),
        SpikePrompt(
            id = "long_creative",
            text = "Write a 300-word short story about a lighthouse keeper who discovers a message in a bottle.",
            maxTokens = 512,
        ),
        SpikePrompt(
            id = "long_technical",
            text = "Describe the differences between TCP and UDP, including handshake behavior, " +
                "ordering guarantees, and typical use cases. Aim for 400 words.",
            maxTokens = 768,
        ),
        SpikePrompt(
            id = "sustained_5min",
            text = "Write a detailed explanation of how a Kalman filter works, including the " +
                "prediction step, the update step, the role of the covariance matrix, and a worked " +
                "numerical example. Use approximately 1000 words.",
            maxTokens = 1024,
        ),
    )
}
