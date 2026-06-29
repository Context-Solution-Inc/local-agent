package com.contextsolutions.localagent.voice

import com.contextsolutions.localagent.voice.VoskDictation.Companion.MAX_CONSECUTIVE_ZERO
import com.contextsolutions.localagent.voice.VoskDictation.Companion.backoffMs
import com.contextsolutions.localagent.voice.VoskDictation.Companion.classifyRead
import com.contextsolutions.localagent.voice.VoskDictation.ReadOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure coverage for the [TargetDataLine.read] classification + backoff that drive the
 * suspend/resume recovery loop (debug PR). No audio hardware — the same hardware-free
 * pattern as [DesktopTtsVoicesTest].
 */
class VoskReadClassifierTest {

    @Test
    fun `positive read is data`() {
        assertEquals(ReadOutcome.Data, classifyRead(read = 4096, consecutiveZero = 0))
        assertEquals(ReadOutcome.Data, classifyRead(read = 1, consecutiveZero = MAX_CONSECUTIVE_ZERO))
    }

    @Test
    fun `negative read is stale (the suspend-resume signature)`() {
        assertEquals(ReadOutcome.Stale, classifyRead(read = -1, consecutiveZero = 0))
    }

    @Test
    fun `a short run of empty reads keeps waiting`() {
        assertEquals(ReadOutcome.KeepWaiting, classifyRead(read = 0, consecutiveZero = 0))
        assertEquals(ReadOutcome.KeepWaiting, classifyRead(read = 0, consecutiveZero = MAX_CONSECUTIVE_ZERO - 1))
    }

    @Test
    fun `a long run of empty reads is stale`() {
        assertEquals(ReadOutcome.Stale, classifyRead(read = 0, consecutiveZero = MAX_CONSECUTIVE_ZERO))
        assertEquals(ReadOutcome.Stale, classifyRead(read = 0, consecutiveZero = MAX_CONSECUTIVE_ZERO + 50))
    }

    @Test
    fun `backoff grows then caps at 3s`() {
        assertEquals(300L, backoffMs(0))
        assertEquals(600L, backoffMs(1))
        assertEquals(1_200L, backoffMs(2))
        // Capped — never blocks recovery for too long.
        assertTrue(backoffMs(10) <= 3_000L)
        assertEquals(3_000L, backoffMs(10))
    }
}
