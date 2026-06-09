package com.contextsolutions.mobileagent.link.transport

import com.securegateway.core.transport.ConnectionState
import com.securegateway.mobile.MobileClient
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [RelayBytePipe] over the Secure Gateway mobile SDK ([MobileClient]). Keeps all
 * `com.securegateway.*` types in androidMain (#23). Mirror of the desktop pipe.
 *
 * The SDK callbacks must be wired **before** `connect()`, so the factory
 * constructs this (after `pair`) and then calls `connect()`. Inbound frames arrive
 * on SDK threads → buffered through an unlimited [Channel].
 */
class AndroidRelayBytePipe(private val client: MobileClient) : RelayBytePipe {

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
    ConnectionState.REVOKED, ConnectionState.SUPERSEDED -> LinkConnectionState.DISABLED
}

internal suspend fun CompletableFuture<Void>.awaitVoid(): Unit =
    suspendCancellableCoroutine { cont ->
        whenComplete { _, error ->
            if (error != null) cont.resumeWithException(error) else cont.resume(Unit)
        }
        cont.invokeOnCancellation { cancel(true) }
    }
