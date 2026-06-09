package com.contextsolutions.mobileagent.link.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Mobile [LinkTransport] over the E2EE relay (relay follow-up to PR #57). Frames
 * link requests onto a [RelayBytePipe] via a [FrameMultiplexer]; the desktop's
 * [FrameDispatcher] answers. Used when a relay subscription is active and the
 * phone paired via the relay QR (vs the LAN `magent://` QR). Same surface as
 * [LanLinkTransport], so the desktop-link engine / sync client are unchanged.
 */
class RelayLinkTransport(
    pipe: RelayBytePipe,
    scope: CoroutineScope,
) : LinkTransport {

    private val mux = FrameMultiplexer(pipe, scope).also { it.start() }

    override val target: String = "relay"

    override suspend fun unary(request: LinkRequest): LinkResponse = mux.unary(request)

    override fun serverStream(request: LinkRequest): Flow<LinkStreamEvent> = mux.serverStream(request)
}
