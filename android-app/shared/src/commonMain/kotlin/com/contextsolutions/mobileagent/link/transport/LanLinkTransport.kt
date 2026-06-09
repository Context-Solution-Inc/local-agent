package com.contextsolutions.mobileagent.link.transport

import com.contextsolutions.mobileagent.platform.HttpEngineFactory
import com.contextsolutions.mobileagent.preferences.DesktopLinkConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * LAN [LinkTransport] (PR #57): the existing plain-HTTP path to the paired
 * desktop's Ktor link server, relocated behind the seam. Reads endpoint + token
 * from [configProvider] **per call** so a re-pair / token rotation is picked up
 * without rebuilding. Behaviour is byte-for-byte the old
 * `DesktopLinkClient` / `LinkSyncHttpClient` / `DesktopLinkInferenceEngine` HTTP
 * code — only the call sites moved.
 *
 * Two clients, mirroring the prior split: a tight-timeout one for unary calls and
 * an un-timed one for the streams (a request timeout would abort an SSE).
 */
class LanLinkTransport(
    httpEngineFactory: HttpEngineFactory,
    private val configProvider: () -> DesktopLinkConfig?,
) : LinkTransport {

    private val client: HttpClient = httpEngineFactory.create()
    private val streamClient: HttpClient = httpEngineFactory.create {
        install(HttpTimeout) {
            connectTimeoutMillis = STREAM_CONNECT_TIMEOUT_MS
            // No whole-request cap (a generation streams arbitrarily long), but a
            // generous read-gap budget. NOTE: `socketTimeoutMillis = null` does NOT
            // disable the read timeout for the OkHttp engine — Ktor only overrides
            // OkHttp's built-in 10 s default when given a non-null value. The
            // desktop can be silent for a while before the first token (it may be
            // cold-loading its local model on remote-LLM fallback), so a 10 s read
            // gap would wrongly abort the turn. Set it explicitly high instead.
            requestTimeoutMillis = null
            socketTimeoutMillis = STREAM_SOCKET_TIMEOUT_MS
        }
    }

    override val target: String
        get() = configProvider()?.baseUrl() ?: ""

    override suspend fun unary(request: LinkRequest): LinkResponse {
        val cfg = configProvider()
        val baseUrl = cfg?.baseUrl() ?: return LinkResponse(503, "link not configured")
        val token = cfg.pairingToken
        return when (request.method) {
            LinkMethod.HEALTH -> doGet(baseUrl, "/health", token, tight = true)
            LinkMethod.PAIR -> doPost(baseUrl, "/pair", token, request.body ?: "{}", tight = true)
            LinkMethod.SYNC_CHANGES -> {
                val since = request.query["since"] ?: "0"
                doGet(baseUrl, "/sync/changes?since=$since", token, tight = false)
            }
            LinkMethod.SYNC_UPSERT -> doPost(baseUrl, "/sync/upsert", token, request.body ?: "{}", tight = false)
            LinkMethod.CHAT, LinkMethod.SYNC_SUBSCRIBE ->
                LinkResponse(400, "${request.method} is a streaming method")
        }
    }

    override fun serverStream(request: LinkRequest): Flow<LinkStreamEvent> = flow {
        val cfg = configProvider()
        val baseUrl = cfg?.baseUrl()
        if (baseUrl == null) {
            emit(LinkStreamEvent.Error(503, "link not configured")); return@flow
        }
        val token = cfg.pairingToken
        val isChat = when (request.method) {
            LinkMethod.CHAT -> true
            LinkMethod.SYNC_SUBSCRIBE -> false
            else -> { emit(LinkStreamEvent.Error(400, "${request.method} is not a streaming method")); return@flow }
        }
        try {
            val statement = if (isChat) {
                streamClient.preparePost("${baseUrl.trimEnd('/')}/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(request.body ?: "{}")
                }
            } else {
                streamClient.prepareGet("${baseUrl.trimEnd('/')}/sync/subscribe") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            }
            statement.execute { response ->
                if (!response.status.isSuccess()) {
                    val err = runCatching { response.bodyAsText() }.getOrDefault("")
                    emit(LinkStreamEvent.Error(response.status.value, err.take(300)))
                    return@execute
                }
                val channel = response.bodyAsChannel()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank() || !line.startsWith("data:")) continue
                    val data = line.substringAfter("data:").trim()
                    if (isChat && data == "[DONE]") break
                    emit(LinkStreamEvent.Data(data))
                }
                emit(LinkStreamEvent.End(200))
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // status 0 ⇒ peer unreachable (refused/timeout/dropped mid-stream); the
            // caller starts a reconnect watch + falls back. HTTP-status errors above
            // mean the desktop is up, so they carry the real status (no watch).
            emit(LinkStreamEvent.Error(0, t.message ?: "stream failed"))
        }
    }

    private suspend fun doGet(baseUrl: String, path: String, token: String, tight: Boolean): LinkResponse = try {
        val r = client.get("${baseUrl.trimEnd('/')}$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            applyTimeout(tight)
        }
        LinkResponse(r.status.value, r.bodyAsText())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        LinkResponse(0, t.message ?: "unreachable")
    }

    private suspend fun doPost(baseUrl: String, path: String, token: String, body: String, tight: Boolean): LinkResponse = try {
        val r = client.post("${baseUrl.trimEnd('/')}$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
            applyTimeout(tight)
        }
        LinkResponse(r.status.value, r.bodyAsText())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        LinkResponse(0, t.message ?: "unreachable")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyTimeout(tight: Boolean) = timeout {
        if (tight) {
            requestTimeoutMillis = HEALTH_TIMEOUT_MS
            connectTimeoutMillis = TIGHT_CONNECT_TIMEOUT_MS
        } else {
            requestTimeoutMillis = CALL_TIMEOUT_MS
            connectTimeoutMillis = SYNC_CONNECT_TIMEOUT_MS
        }
    }

    private companion object {
        const val TIGHT_CONNECT_TIMEOUT_MS = 2_500L
        const val HEALTH_TIMEOUT_MS = 3_000L
        const val SYNC_CONNECT_TIMEOUT_MS = 3_000L
        const val CALL_TIMEOUT_MS = 15_000L
        const val STREAM_CONNECT_TIMEOUT_MS = 30_000L
        // Max silence between bytes on a chat/subscribe stream. Generous enough to
        // cover the desktop cold-loading its local model before the first token,
        // while still detecting a genuinely dead desktop in bounded time.
        const val STREAM_SOCKET_TIMEOUT_MS = 300_000L
    }
}
