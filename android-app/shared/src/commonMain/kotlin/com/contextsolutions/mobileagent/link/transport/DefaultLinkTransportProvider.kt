package com.contextsolutions.mobileagent.link.transport

import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Selects the active [LinkTransport] from the link config. LAN is the default and
 * the fallback; when `accessMode == RELAY` the relay transport is used once the
 * relay pipe is connected.
 *
 * The relay pipe is established eagerly in the background when the config becomes
 * relay-configured (pairing + connect is async), and torn down when it isn't.
 * [current] is non-suspending — it returns the cached relay transport only while
 * the pipe is [LinkConnectionState.UP], else `null` so the router falls back to the
 * on-device model. [onRelayConnectivityChanged] re-decides the backend as the pipe
 * comes up/down (the relay has no pollable health URL).
 */
class DefaultLinkTransportProvider(
    private val preferences: DesktopLinkPreferences,
    private val lan: LinkTransport,
    private val relayFactory: RelayBytePipeFactory? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onRelayConnectivityChanged: () -> Unit = {},
    private val logger: (String) -> Unit = {},
) : LinkTransportProvider {

    private val mutex = Mutex()

    @Volatile
    private var relay: RelayLinkTransport? = null

    @Volatile
    private var relayPipe: RelayBytePipe? = null

    init {
        if (relayFactory != null) {
            scope.launch {
                preferences.configFlow()
                    .map { it.isRelayConfigured to it.relayQrJson }
                    .distinctUntilChanged()
                    .collectLatest { (configured, qrJson) ->
                        teardownRelay()
                        if (!configured) return@collectLatest
                        runCatching {
                            val pipe = relayFactory.create(qrJson)
                            if (pipe == null) {
                                logger("relay pipe unavailable")
                                return@runCatching
                            }
                            val transport = RelayLinkTransport(pipe, scope)
                            mutex.withLock { relayPipe = pipe; relay = transport }
                            logger("relay pipe established")
                            // Re-decide the backend as the relay comes up/down.
                            launch { pipe.state.collect { onRelayConnectivityChanged() } }
                            onRelayConnectivityChanged()
                        }.onFailure { logger("relay setup failed: ${it.message}") }
                    }
            }
        }
    }

    override fun current(): LinkTransport? {
        val cfg = preferences.config()
        if (!cfg.isLinkConfigured) return null
        if (cfg.accessMode == LinkAccessMode.RELAY) {
            val pipe = relayPipe ?: return null
            return if (pipe.state.value == LinkConnectionState.UP) relay else null
        }
        return lan
    }

    private suspend fun teardownRelay() {
        val pipe = mutex.withLock {
            val p = relayPipe
            relayPipe = null
            relay = null
            p
        }
        pipe?.let { runCatching { it.close() } }
    }
}
