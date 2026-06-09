package com.contextsolutions.mobileagent.link

import com.contextsolutions.mobileagent.link.transport.LinkMethod
import com.contextsolutions.mobileagent.link.transport.LinkRequest
import com.contextsolutions.mobileagent.link.transport.LinkRequestHandler
import com.contextsolutions.mobileagent.link.transport.LinkResponse
import com.contextsolutions.mobileagent.link.transport.LinkStreamEvent
import com.contextsolutions.mobileagent.preferences.DesktopLinkConfig
import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.writeStringUtf8
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.collect

/**
 * The desktop half of the mobile↔desktop link (PR #57): a small Ktor (CIO) HTTP
 * server bound on `0.0.0.0` so a paired phone on the LAN can reach it. Plain HTTP
 * by design (trusted LAN, no SSL); a QR-provisioned bearer token gates every
 * route except `/ping` + `/subscribe/callback`, so random LAN clients are ignored.
 *
 * The route bodies live in the shared [LinkRequestHandler]
 * ([DesktopLinkRequestHandler]) — the SAME implementation the relay frame
 * dispatcher calls — so this server only owns LAN concerns: bearer auth, the
 * HTTP/SSE marshaling, the pairing-token lifecycle, and the held-subscriber count.
 *
 * Endpoints:
 *  - `GET /ping` — unauthenticated liveness (used to find the bound port).
 *  - `GET /subscribe/callback` — PR #74 Stripe Checkout redirect (browser-facing).
 *  - `GET /health` / `POST /pair` / `GET /sync/changes` / `POST /sync/upsert` —
 *    token-gated unary calls → [LinkRequestHandler.handleUnary].
 *  - `POST /v1/chat/completions` / `GET /sync/subscribe` — token-gated SSE streams
 *    → [LinkRequestHandler.handleStream].
 */
class DesktopLinkServer(
    private val preferences: DesktopLinkPreferences,
    private val handler: LinkRequestHandler,
    /** Updated as phones connect/disconnect (their held `/sync/subscribe` SSE). */
    private val connectionStatus: MutableDesktopLinkConnectionStatus? = null,
    /**
     * PR #74 — handles the Stripe Checkout success redirect (`GET
     * /subscribe/callback?claim_code=…`). The browser is redirected here by the
     * gateway after payment; the handler exchanges the one-time code for the
     * account credential. Unauthenticated (the browser has no bearer); loopback
     * binding + a single-use code are the controls. Returns true on success.
     */
    private val onSubscribeCallback: (suspend (claimCode: String) -> Boolean)? = null,
    private val preferredPort: Int = DesktopLinkConfig.DEFAULT_PORT,
    private val logger: (String) -> Unit = {},
) {
    // Number of phones currently holding a /sync/subscribe stream. The mobile keeps
    // it open while foregrounded + linked, so >0 means "a phone is connected".
    private val activeSubscribers = java.util.concurrent.atomic.AtomicInteger(0)

    private fun subscriberOpened() {
        activeSubscribers.incrementAndGet()
        connectionStatus?.set(true)
    }

    private fun subscriberClosed() {
        if (activeSubscribers.decrementAndGet() <= 0) connectionStatus?.set(false)
    }

    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    @Volatile
    var boundPort: Int = 0
        private set

    /** Ensure a pairing token exists (minted once), start the server, return the bound port. */
    @OptIn(ExperimentalUuidApi::class)
    fun start(): Int {
        ensurePairingToken()
        val port = runCatching { startOn(preferredPort) }.getOrElse {
            logger("port $preferredPort unavailable (${it.message}); using an ephemeral port")
            startOn(0)
        }
        boundPort = port
        logger("listening on 0.0.0.0:$port")
        return port
    }

    fun stop() {
        runCatching { server?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000) }
        server = null
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun ensurePairingToken() {
        val cfg = preferences.config()
        if (cfg.pairingToken.isBlank()) {
            preferences.setConfig(cfg.copy(pairingToken = Uuid.random().toString()))
        }
    }

    private fun startOn(port: Int): Int {
        val s = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    logger("route error: ${cause.message}")
                    call.respondText(
                        """{"error":${jsonString(cause.message ?: "error")}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError,
                    )
                }
            }
            routing {
                get("/ping") { call.respondText("ok") }

                // PR #74 — Stripe Checkout success redirect. Browser-facing (no
                // bearer); the gateway only ever redirects a one-time claim_code
                // to this loopback address.
                get("/subscribe/callback") {
                    val code = call.request.queryParameters["claim_code"].orEmpty()
                    val canceled = call.request.queryParameters["status"] == "canceled"
                    val ok = !canceled && code.isNotBlank() &&
                        (onSubscribeCallback?.invoke(code) ?: false)
                    val message = when {
                        canceled -> "Checkout canceled. You can close this tab."
                        ok -> "Subscription activated. You can return to the Mobile Agent app."
                        else -> "Could not finish activation. Return to the app and try again."
                    }
                    call.respondText(
                        "<!doctype html><meta charset=utf-8><title>Mobile Agent</title>" +
                            "<body style=\"font-family:sans-serif;text-align:center;margin-top:4em\">" +
                            "<h2>$message</h2></body>",
                        ContentType.Text.Html,
                    )
                }

                get("/health") {
                    if (!call.authorized()) return@get call.unauthorized()
                    call.respondUnary(handler.handleUnary(LinkRequest(LinkMethod.HEALTH)))
                }

                post("/v1/chat/completions") {
                    if (!call.authorized()) return@post call.unauthorized()
                    val body = call.receiveText()
                    call.respondStream(handler.handleStream(LinkRequest(LinkMethod.CHAT, body = body)))
                }

                post("/pair") {
                    if (!call.authorized()) return@post call.unauthorized()
                    val body = call.receiveText()
                    call.respondUnary(handler.handleUnary(LinkRequest(LinkMethod.PAIR, body = body)))
                }

                get("/sync/changes") {
                    if (!call.authorized()) return@get call.unauthorized()
                    val since = call.request.queryParameters["since"] ?: "0"
                    call.respondUnary(
                        handler.handleUnary(LinkRequest(LinkMethod.SYNC_CHANGES, query = mapOf("since" to since))),
                    )
                }

                post("/sync/upsert") {
                    if (!call.authorized()) return@post call.unauthorized()
                    val body = call.receiveText()
                    call.respondUnary(handler.handleUnary(LinkRequest(LinkMethod.SYNC_UPSERT, body = body)))
                }

                get("/sync/subscribe") {
                    if (!call.authorized()) return@get call.unauthorized()
                    subscriberOpened()
                    try {
                        call.respondStream(handler.handleStream(LinkRequest(LinkMethod.SYNC_SUBSCRIBE)))
                    } finally {
                        subscriberClosed()
                    }
                }
            }
        }
        s.start(wait = false)
        server = s
        return s.resolvedConnectorsBlocking().first().port
    }

    private suspend fun ApplicationCall.respondUnary(response: LinkResponse) =
        respondText(response.body, ContentType.Application.Json, HttpStatusCode.fromValue(response.status))

    /**
     * Marshal a handler stream back over SSE, flushing each event to the socket so
     * the phone sees tokens as they're produced. NOTE: `respondTextWriter` +
     * `Writer.flush()` does NOT stream on Ktor CIO — the Writer's flush only pushes
     * into the response `ByteWriteChannel`, which then buffers until it fills or the
     * response completes (the whole reply effectively batches, and a long turn times
     * out the client before anything arrives). `respondBytesWriter` +
     * `ByteWriteChannel.flush()` forces each event out to the socket.
     */
    private suspend fun ApplicationCall.respondStream(stream: kotlinx.coroutines.flow.Flow<LinkStreamEvent>) {
        respondBytesWriter(contentType = ContentType.parse("text/event-stream")) {
            try {
                stream.collect { event ->
                    val sse = when (event) {
                        is LinkStreamEvent.Data -> "data: ${event.body}\n\n"
                        is LinkStreamEvent.End -> "data: [DONE]\n\n"
                        is LinkStreamEvent.Error -> "data: ${event.message}\n\ndata: [DONE]\n\n"
                    }
                    writeStringUtf8(sse)
                    flush()
                }
            } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                throw c
            } catch (_: Exception) {
                // The paired phone hung up mid-stream — the write fails with
                // "Broken pipe" or Ktor's "Cannot write to a channel". Nothing more
                // to send; the upstream engine cancels via the closed collector. Stop
                // quietly instead of surfacing it as a route error.
            }
        }
    }

    private fun ApplicationCall.authorized(): Boolean {
        val token = preferences.config().pairingToken
        if (token.isBlank()) return false
        val header = request.headers["Authorization"] ?: return false
        return header.removePrefix("Bearer ").trim() == token
    }

    private suspend fun ApplicationCall.unauthorized() =
        respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
}

/** Minimal JSON string-escape for the error wrapper (avoids pulling a serializer for one field). */
private fun jsonString(value: String): String =
    buildString {
        append('"')
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

/** Blocking read of the resolved port (CIO resolves connectors asynchronously). */
private fun EmbeddedServer<*, *>.resolvedConnectorsBlocking() =
    kotlinx.coroutines.runBlocking { engine.resolvedConnectors() }
