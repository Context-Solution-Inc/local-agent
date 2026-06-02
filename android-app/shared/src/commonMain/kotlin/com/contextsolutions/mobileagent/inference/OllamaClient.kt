package com.contextsolutions.mobileagent.inference

import com.contextsolutions.mobileagent.platform.HttpEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Thin Ktor client for an Ollama server's *control-plane* endpoints (PR #56):
 * model discovery ([listModels]) and a reachability probe ([health]). The
 * streaming generation path lives in [OllamaInferenceEngine] — kept separate
 * because generation needs an un-timed client (long SSE streams) while these
 * short calls want a tight per-request timeout so a misconfigured host fails
 * fast (Settings "Test connection" + the engine's load-time fallback probe).
 *
 * Built from the platform [HttpEngineFactory] so it reuses the same engine
 * (OkHttp on Android, CIO on desktop), JSON negotiation and redacting logger as
 * the Brave search clients. There is one [OllamaClient] for the process; it is
 * shared by the Settings UI and the engine so the Ollama wire shapes are
 * decoded in exactly one place.
 */
class OllamaClient internal constructor(private val client: HttpClient) {

    constructor(httpEngineFactory: HttpEngineFactory) : this(httpEngineFactory.create())

    /**
     * GET `<baseUrl>/api/tags` → the installed models. Vision-capability is a
     * best-effort heuristic on the model name + reported families (Ollama
     * surfaces a `clip`/`mllama` family or a known vision name for multimodal
     * models); it only sorts the Settings vision dropdown, never gates anything.
     * Returns an empty list on any error (caller treats empty as "unreachable").
     */
    suspend fun listModels(baseUrl: String): List<OllamaModel> = try {
        val response = client.get("${baseUrl.trimEnd('/')}/api/tags") {
            timeout { requestTimeoutMillis = LIST_TIMEOUT_MS; connectTimeoutMillis = CONNECT_TIMEOUT_MS }
        }
        if (!response.status.isSuccess()) {
            emptyList()
        } else {
            parseModels(response.bodyAsText())
        }
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * Cheap reachability check used by [OllamaInferenceEngine.loadModel] before
     * committing to the remote backend; on false the router falls back to the
     * on-device model. Tight timeout so an unreachable host doesn't stall the
     * first turn.
     */
    suspend fun health(baseUrl: String): Boolean = try {
        client.get("${baseUrl.trimEnd('/')}/api/tags") {
            timeout { requestTimeoutMillis = HEALTH_TIMEOUT_MS; connectTimeoutMillis = CONNECT_TIMEOUT_MS }
        }.status.isSuccess()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        false
    }

    private fun parseModels(raw: String): List<OllamaModel> = runCatching {
        JSON.parseToJsonElement(raw).jsonObject["models"]?.jsonArray.orEmpty().mapNotNull { el ->
            val obj = el.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val families = obj["details"]?.jsonObject?.get("families")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content }.orEmpty()
            OllamaModel(name = name, isVisionCapable = isVisionCapable(name, families))
        }.sortedBy { it.name }
    }.getOrDefault(emptyList())

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        const val CONNECT_TIMEOUT_MS = 2_500L
        const val HEALTH_TIMEOUT_MS = 3_000L
        const val LIST_TIMEOUT_MS = 5_000L

        // Families/name fragments that signal a multimodal (vision) model.
        val VISION_FAMILIES = setOf("clip", "mllama")
        val VISION_NAME_HINTS = listOf("llava", "vl", "vision", "bakllava", "moondream", "minicpm-v")

        fun isVisionCapable(name: String, families: List<String>): Boolean {
            if (families.any { it.lowercase() in VISION_FAMILIES }) return true
            val n = name.lowercase()
            return VISION_NAME_HINTS.any { n.contains(it) }
        }
    }
}

/** One installed Ollama model, as surfaced by `/api/tags`. */
data class OllamaModel(
    val name: String,
    val isVisionCapable: Boolean,
)
