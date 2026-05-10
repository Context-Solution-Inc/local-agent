package com.contextsolutions.mobileagent.classifier

import com.contextsolutions.mobileagent.agent.TimeContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightRouterTest {

    // 2026-05-10 → "yesterday" = 2026-05-09 (covered in rewriter tests).
    private val timeContext = TimeContext(
        now = LocalDateTime(2026, 5, 10, 14, 32),
        timeZoneId = "America/Toronto",
        timeZoneAbbreviation = "EDT",
        utcOffset = "-04:00",
    )
    private val rewriter = QueryRewriter { timeContext }
    private val config = PreflightConfig.DEFAULT

    private fun makeRouter(
        engine: ClassifierEngine,
        searchAvailable: Boolean = true,
        logSink: MutableList<String> = mutableListOf(),
    ) = PreflightRouter(
        engine = engine,
        tokenizer = stubTokenizer,
        rewriter = rewriter,
        configProvider = { config },
        searchAvailableProvider = { searchAvailable },
        logger = { logSink.add(it) },
    )

    // -- Three-band routing ------------------------------------------------

    @Test
    fun high_band_with_clean_query_emits_FireSearch() = runTest {
        // logits chosen so softmax assigns ~0.95 to search_required (index 0).
        val engine = stubEngine(preflightLogits = floatArrayOf(5f, 0f, 0f))
        val log = mutableListOf<String>()
        val decision = makeRouter(engine, logSink = log).route("did the eagles win yesterday")
        assertTrue("expected FireSearch, got $decision", decision is PreflightDecision.FireSearch)
        decision as PreflightDecision.FireSearch
        assertEquals("did the eagles win yesterday", decision.originalQuery)
        assertEquals("did the eagles win 2026-05-09", decision.rewrittenQuery)
        assertTrue(decision.pSearchRequired > 0.9f)
        assertTrue("log line missing", log.any { it.contains("FireSearch") })
    }

    @Test
    fun high_band_with_memory_reference_falls_through_with_RewriterAbort() = runTest {
        val engine = stubEngine(preflightLogits = floatArrayOf(5f, 0f, 0f))
        val decision = makeRouter(engine).route("did my team win yesterday")
        assertTrue(decision is PreflightDecision.FallThrough)
        decision as PreflightDecision.FallThrough
        assertEquals(FallThroughReason.RewriterAbort, decision.reason)
        assertNotNull(decision.pSearchRequired)
    }

    @Test
    fun low_band_emits_SkipSearch() = runTest {
        // logits chosen so softmax assigns ~0.95 to search_not_required (index 1).
        val engine = stubEngine(preflightLogits = floatArrayOf(0f, 5f, 0f))
        val decision = makeRouter(engine).route("what is photosynthesis")
        assertTrue(decision is PreflightDecision.SkipSearch)
        decision as PreflightDecision.SkipSearch
        assertTrue("expected p_search_required < lowBand, got ${decision.pSearchRequired}",
            decision.pSearchRequired < config.thresholds.lowBand)
    }

    @Test
    fun middle_band_emits_FallThrough_MiddleBand() = runTest {
        // Equal logits → uniform softmax → pSearch ≈ 0.33, in middle band.
        val engine = stubEngine(preflightLogits = floatArrayOf(1f, 1f, 1f))
        val decision = makeRouter(engine).route("should I buy aapl")
        assertTrue(decision is PreflightDecision.FallThrough)
        decision as PreflightDecision.FallThrough
        assertEquals(FallThroughReason.MiddleBand, decision.reason)
    }

    @Test
    fun classifier_returning_null_emits_FallThrough_ClassifierUnavailable() = runTest {
        val engine = stubEngine(preflightLogits = null) // null → engine.classify returns null
        val log = mutableListOf<String>()
        val router = makeRouter(engine, logSink = log)
        val decision = router.route("anything goes here")
        assertTrue(decision is PreflightDecision.FallThrough)
        decision as PreflightDecision.FallThrough
        assertEquals(FallThroughReason.ClassifierUnavailable, decision.reason)
        assertNull(decision.pSearchRequired)

        // Second call MUST NOT log the unavailability again (we documented
        // log-once-per-construction in the router class doc).
        log.clear()
        router.route("another query")
        assertFalse("classifier-unavailable line should log only once",
            log.any { it.contains("classifier unavailable") })
    }

    @Test
    fun search_disabled_short_circuits_before_classify() = runTest {
        val engine = throwingEngine() // would explode if reached
        val log = mutableListOf<String>()
        val decision = makeRouter(engine, searchAvailable = false, logSink = log)
            .route("did the eagles win")
        assertEquals(PreflightDecision.SearchDisabled, decision)
        assertTrue(log.any { it.contains("SearchDisabled") })
    }

    // -- Stub fixtures ------------------------------------------------------

    private val stubTokenizer = run {
        // Build a minimal vocab so WordPieceTokenizer.encodeSingle works without
        // bundling vocab.txt into commonTest. The tokenizer's actual byte-exact
        // behavior is covered by WordPieceTokenizerFixtureTest.
        val tokens = mutableMapOf<String, Int>()
        listOf("[PAD]" to 0, "[UNK]" to 100, "[CLS]" to 101, "[SEP]" to 102).forEach {
            tokens[it.first] = it.second
        }
        // Pad with placeholders so the vocab has any size.
        WordPieceTokenizer(Vocab(tokens, tokens.entries.associate { (k, v) -> v to k }))
    }

    private fun stubEngine(preflightLogits: FloatArray?): ClassifierEngine = object : ClassifierEngine {
        override val isLoaded: Boolean = preflightLogits != null
        override suspend fun warmUp(): ClassifierAccelerator? = ClassifierAccelerator.CPU
        override suspend fun classify(inputIds: LongArray, attentionMask: LongArray): ClassifierOutput? =
            preflightLogits?.let {
                ClassifierOutput(
                    preflightLogits = it,
                    presenceLogits = floatArrayOf(0f, 0f),
                    categoryLogits = FloatArray(6),
                )
            }
        override suspend fun unload() = Unit
    }

    private fun throwingEngine(): ClassifierEngine = object : ClassifierEngine {
        override val isLoaded: Boolean get() = error("must not be called")
        override suspend fun warmUp(): ClassifierAccelerator? = error("must not be called")
        override suspend fun classify(inputIds: LongArray, attentionMask: LongArray): ClassifierOutput? =
            error("router must short-circuit before classify when search disabled")
        override suspend fun unload() = error("must not be called")
    }
}
