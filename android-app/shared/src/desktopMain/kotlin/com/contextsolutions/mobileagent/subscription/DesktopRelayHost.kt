package com.contextsolutions.mobileagent.subscription

import com.contextsolutions.mobileagent.link.transport.DesktopRelayBytePipe
import com.contextsolutions.mobileagent.link.transport.FrameDispatcher
import com.contextsolutions.mobileagent.link.transport.LinkConnectionState
import com.contextsolutions.mobileagent.link.transport.LinkRequestHandler
import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import com.securegateway.desktop.DesktopClient
import com.securegateway.desktop.DesktopConfig
import com.securegateway.desktop.SecureGateway
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The desktop relay host (relay follow-up to PR #74). When a subscription is
 * active, the desktop becomes a relay *request-server*: it mints the relay
 * pairing QR, waits for the phone to pair, connects to the Secure Gateway relay,
 * and serves framed link requests through the SAME [LinkRequestHandler] the LAN
 * Ktor server uses ([com.contextsolutions.mobileagent.link.DesktopLinkRequestHandler]).
 *
 * The LAN server keeps running alongside this (concurrent) — relay covers
 * remote access, LAN stays the fast same-network path and the offline/revoked
 * fallback. All `com.securegateway.*` types stay in desktopMain (#23).
 *
 * The account secret is embedded in the QR by the SDK's `generatePairingQr`
 * (decision: the phone has no subscription of its own), so the scanned QR lets
 * the phone issue connection tokens.
 */
class DesktopRelayHost(
    private val prefs: SubscriptionPreferences,
    private val secureStorage: SecureStorage,
    private val gatewayBaseUrl: String,
    private val relayWsUrl: String,
    private val keyStorePath: Path,
    private val logger: (String) -> Unit = {},
) {
    private var client: DesktopClient? = null
    private var pipe: DesktopRelayBytePipe? = null

    private val _state = MutableStateFlow(LinkConnectionState.DISABLED)
    val state: StateFlow<LinkConnectionState> = _state.asStateFlow()

    private fun ensureClient(): DesktopClient = client ?: newClient().also { client = it }

    private fun newClient(): DesktopClient {
        val state = prefs.state()
        val secret = secureStorage.get(SecureStorageKeys.RELAY_ACCOUNT_SECRET)
            ?: error("no relay account secret; not subscribed")
        val config = DesktopConfig().apply {
            authUrl = gatewayBaseUrl
            relayUrl = relayWsUrl
            accountSecret = secret
            licenseId = state.licenseId
        }.keyStoreFile(keyStorePath)
        return SecureGateway.desktop(config)
    }

    /** Mint the relay pairing QR as the JSON string embedded in the QR (B4); carries the account secret. */
    fun generatePairingQr(): String = ensureClient().generatePairingQr().toJson()

    /** Block until the phone completes pairing (blocking poll — call on an IO dispatcher). */
    fun awaitPairing(timeout: Duration) = ensureClient().awaitPairing(timeout)

    /**
     * Wire the relay byte-pipe + frame dispatcher, then open the relay session.
     * `connect()` does blocking HTTP (token issue) — call on an IO dispatcher.
     */
    fun connectAndServe(handler: LinkRequestHandler, scope: CoroutineScope) {
        val c = ensureClient()
        val p = DesktopRelayBytePipe(c) // wires onMessage/onStateChange before connect()
        scope.launch { p.state.collect { _state.value = it } }
        FrameDispatcher(p, handler, scope, logger).start()
        pipe = p
        c.connect()
    }

    fun close() {
        runCatching { client?.close() }
        client = null
        pipe = null
        _state.value = LinkConnectionState.DISABLED
    }
}
