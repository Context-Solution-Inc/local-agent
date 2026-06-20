package com.contextsolutions.localagent.link.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Resource-bound checks for the relay request-server (security M5 / audit F4): a
 * lower-trust paired phone must not be able to exhaust the desktop by opening
 * unbounded concurrent streams (each an LLM generation or a subprocess) or unary
 * requests, and a replayed [LinkFrame.id] must not silently overwrite — or leak —
 * a live stream. Frames are driven straight at a [FrameDispatcher] and its outbound
 * frames captured, so the cap behaviour is observed at the wire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrameDispatcherLimitsTest {

    /** Drives REQUEST/CANCEL frames into a dispatcher and records every frame it sends back. */
    private class ServerRig(
        scope: CoroutineScope,
        handler: LinkRequestHandler,
        maxStreams: Int = FrameDispatcher.DEFAULT_MAX_CONCURRENT_STREAMS,
        maxUnary: Int = FrameDispatcher.DEFAULT_MAX_CONCURRENT_UNARY,
    ) {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val out = mutableListOf<LinkFrame>()
        val logs = mutableListOf<String>()

        private val pipe = object : RelayBytePipe {
            override suspend fun send(bytes: ByteArray) {
                out.add(LinkFrameCodec.decode(bytes))
            }
            override val inbound: Flow<ByteArray> = this@ServerRig.inbound.receiveAsFlow()
            override val state: StateFlow<LinkConnectionState> = MutableStateFlow(LinkConnectionState.UP)
            override suspend fun close() {}
        }

        init {
            FrameDispatcher(
                pipe, handler, scope,
                logger = { logs.add(it) },
                maxConcurrentStreams = maxStreams,
                maxConcurrentUnary = maxUnary,
            ).start()
        }

        suspend fun request(id: Long, method: LinkMethod) =
            inbound.send(LinkFrameCodec.encode(LinkFrame(id = id, kind = FrameKind.REQUEST, method = method)))

        suspend fun cancel(id: Long) =
            inbound.send(LinkFrameCodec.encode(LinkFrame(id = id, kind = FrameKind.CANCEL)))

        fun framesFor(id: Long) = out.filter { it.id == id }
    }

    /** Streams stay open until cancelled; unary calls optionally gate on [unaryGate]. */
    private class ControllableHandler(
        private val unaryGate: CompletableDeferred<Unit>? = null,
    ) : LinkRequestHandler {
        override suspend fun handleUnary(request: LinkRequest): LinkResponse {
            unaryGate?.await()
            return LinkResponse(200, "ok")
        }

        override fun handleStream(request: LinkRequest): Flow<LinkStreamEvent> = flow {
            emit(LinkStreamEvent.Data("open"))
            awaitCancellation()
        }
    }

    @Test
    fun excessConcurrentStreamsRejectedWith429() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val rig = ServerRig(scope, ControllableHandler(), maxStreams = 2)

        rig.request(1, LinkMethod.CHAT)
        rig.request(2, LinkMethod.CHAT)
        rig.request(3, LinkMethod.CHAT)
        advanceUntilIdle()

        // In-cap streams opened (one Data each), no error.
        for (id in listOf(1L, 2L)) {
            val frames = rig.framesFor(id)
            assertEquals(1, frames.count { it.kind == FrameKind.STREAM_DATA }, "stream $id should have opened")
            assertTrue(frames.none { it.kind == FrameKind.STREAM_ERROR }, "stream $id should not error")
        }
        // The over-cap stream is rejected and never opened.
        val third = rig.framesFor(3)
        assertEquals(1, third.size, "over-cap stream gets exactly one frame")
        assertEquals(FrameKind.STREAM_ERROR, third.single().kind)
        assertEquals(FrameDispatcher.STATUS_OVERLOADED, third.single().status)
        assertTrue(third.none { it.kind == FrameKind.STREAM_DATA }, "over-cap stream must not run")

        scope.cancel()
    }

    @Test
    fun duplicateStreamIdRejectedWith409AndDoesNotRelaunch() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val rig = ServerRig(scope, ControllableHandler(), maxStreams = 8)

        rig.request(1, LinkMethod.CHAT)
        advanceUntilIdle()
        rig.request(1, LinkMethod.CHAT) // replay the live id
        advanceUntilIdle()

        val frames = rig.framesFor(1)
        // Exactly one open (the duplicate did NOT launch a second stream — no leak).
        assertEquals(1, frames.count { it.kind == FrameKind.STREAM_DATA }, "duplicate must not start a second stream")
        val error = frames.single { it.kind == FrameKind.STREAM_ERROR }
        assertEquals(FrameDispatcher.STATUS_DUPLICATE, error.status)
        // The original stream is not terminated by the dispatcher (still awaiting cancellation).
        assertTrue(frames.none { it.kind == FrameKind.STREAM_END }, "original stream must stay live")

        scope.cancel()
    }

    @Test
    fun cancellingAStreamFreesItsSlot() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val rig = ServerRig(scope, ControllableHandler(), maxStreams = 2)

        rig.request(1, LinkMethod.CHAT)
        rig.request(2, LinkMethod.CHAT)
        rig.request(3, LinkMethod.CHAT) // rejected — at cap
        advanceUntilIdle()
        assertEquals(FrameDispatcher.STATUS_OVERLOADED, rig.framesFor(3).single().status)

        rig.cancel(1) // frees a slot
        rig.request(4, LinkMethod.CHAT)
        advanceUntilIdle()

        val fourth = rig.framesFor(4)
        assertEquals(1, fourth.count { it.kind == FrameKind.STREAM_DATA }, "a freed slot must accept a new stream")
        assertTrue(fourth.none { it.kind == FrameKind.STREAM_ERROR }, "stream 4 should not be rejected")

        scope.cancel()
    }

    @Test
    fun excessConcurrentUnaryRejectedWith429() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val rig = ServerRig(scope, ControllableHandler(unaryGate = gate), maxUnary = 1)

        rig.request(1, LinkMethod.HEALTH) // enters handler, suspends on the gate (in-flight = 1)
        advanceUntilIdle()
        rig.request(2, LinkMethod.HEALTH) // over cap → rejected immediately
        advanceUntilIdle()

        val rejected = rig.framesFor(2).single()
        assertEquals(FrameKind.RESPONSE, rejected.kind)
        assertEquals(FrameDispatcher.STATUS_OVERLOADED, rejected.status)
        assertTrue(rig.framesFor(1).isEmpty(), "the in-flight unary has not responded yet")

        gate.complete(Unit) // release the in-flight call → it completes and frees the slot
        advanceUntilIdle()
        val first = rig.framesFor(1).single()
        assertEquals(FrameKind.RESPONSE, first.kind)
        assertEquals(200, first.status)

        scope.cancel()
    }

    @Test
    fun acceptsLogInFlightCountAndWarnNearCap() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val rig = ServerRig(scope, ControllableHandler(), maxStreams = 3)

        rig.request(1, LinkMethod.CHAT) // 1/3 — plain
        rig.request(2, LinkMethod.CHAT) // 2/3 — near cap (within NEAR_CAP_MARGIN) → WARN
        rig.request(3, LinkMethod.CHAT) // 3/3 — fills cap → WARN
        advanceUntilIdle()

        assertTrue(rig.logs.any { it == "stream 1 accepted (1/3)" }, "first accept logs count: ${rig.logs}")
        assertTrue(rig.logs.any { it.startsWith("WARN stream 2 accepted (2/3)") }, "near-cap accept warns: ${rig.logs}")
        assertTrue(rig.logs.any { it.startsWith("WARN stream 3 accepted (3/3)") }, "cap-filling accept warns: ${rig.logs}")
        assertTrue(rig.logs.none { it.startsWith("WARN stream 1") }, "below-margin accept must not warn: ${rig.logs}")

        scope.cancel()
    }

    @Test
    fun decodeRejectsOversizedFrame() {
        val tooBig = ByteArray(LinkFrameCodec.MAX_FRAME_BYTES + 1)
        assertFailsWith<IllegalArgumentException> { LinkFrameCodec.decode(tooBig) }
    }
}
