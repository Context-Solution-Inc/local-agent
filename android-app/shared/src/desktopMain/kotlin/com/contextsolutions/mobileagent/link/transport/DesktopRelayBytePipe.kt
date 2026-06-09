package com.contextsolutions.mobileagent.link.transport

import com.securegateway.core.transport.ConnectionState
import com.securegateway.desktop.DesktopClient
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [RelayBytePipe] over the Secure Gateway desktop SDK ([DesktopClient]). Keeps all
 * `com.securegateway.*` types in desktopMain (#23).
 *
 * The SDK callbacks (`onMessage`, `onStateChange`) must be wired **before**
 * `connect()`, so this wraps an unconnected client; the caller
 * ([DesktopRelayHost]) calls `connect()` after constructing the pipe. Inbound
 * frames arrive on SDK threads → buffered through an unlimited [Channel] so the
 * coroutine collector never drops one.
 */
class DesktopRelayBytePipe(private val client: DesktopClient) : RelayBytePipe {

    private val inboundChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(LinkConnectionState.DOWN)

    init {
        client.onMessage { bytes -> inboundChannel.trySend(bytes) }
        client.onStateChange { state -> _state.value = state.toLinkState() }
    }

    override suspend fun send(bytes: ByteArray) = client.send(bytes).awaitVoid()

    override val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()

    override val state: StateFlow<LinkConnectionState> = _state.asStateFlow()

    override suspend fun close() {
        runCatching { client.close() }
        inboundChannel.close()
        _state.value = LinkConnectionState.DISABLED
    }
}

internal fun ConnectionState.toLinkState(): LinkConnectionState = when (this) {
    ConnectionState.CONNECTED -> LinkConnectionState.UP
    ConnectionState.RECONNECTING, ConnectionState.PEER_OFFLINE -> LinkConnectionState.DOWN
    // Terminal (close 4001/4004) — disable so callers fall back to LAN/local.
    ConnectionState.REVOKED, ConnectionState.SUPERSEDED -> LinkConnectionState.DISABLED
}

/** Bridge the SDK's `CompletableFuture<Void>` (peer ack) into a cancellable suspend. */
internal suspend fun CompletableFuture<Void>.awaitVoid(): Unit =
    suspendCancellableCoroutine { cont ->
        whenComplete { _, error ->
            if (error != null) cont.resumeWithException(error) else cont.resume(Unit)
        }
        cont.invokeOnCancellation { cancel(true) }
    }
