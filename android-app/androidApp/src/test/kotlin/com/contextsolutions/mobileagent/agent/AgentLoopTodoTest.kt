package com.contextsolutions.mobileagent.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.mobileagent.classifier.ClassifierAccelerator
import com.contextsolutions.mobileagent.classifier.ClassifierEngine
import com.contextsolutions.mobileagent.classifier.ClassifierOutput
import com.contextsolutions.mobileagent.classifier.PreflightConfig
import com.contextsolutions.mobileagent.classifier.PreflightRouter
import com.contextsolutions.mobileagent.classifier.QueryRewriter
import com.contextsolutions.mobileagent.classifier.Vocab
import com.contextsolutions.mobileagent.classifier.WordPieceTokenizer
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import com.contextsolutions.mobileagent.inference.FinishReason
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import com.contextsolutions.mobileagent.inference.ToolDispatcher
import com.contextsolutions.mobileagent.search.BraveKeyProvider
import com.contextsolutions.mobileagent.search.BraveSearchClient
import com.contextsolutions.mobileagent.search.BraveSearchResult
import com.contextsolutions.mobileagent.search.SearchCacheDao
import com.contextsolutions.mobileagent.search.SearchService
import com.contextsolutions.mobileagent.todo.SqlDelightTodoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Locks the load-bearing contract introduced by PR #15: TODO turns are
 * served deterministically and a partial-match intent (parser returns
 * null) NEVER falls through to the LLM. The test asserts that
 * `engine.generate` is not invoked for any TODO-shaped input — that
 * invariant is the reliability guarantee the structural comment in
 * [AgentLoop.run] documents.
 *
 * Also covers the clock-vs-todo precedence rule: clock branch runs first,
 * so phrases containing both keywords resolve to clock.
 */
class AgentLoopTodoTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: MobileAgentDatabase
    private lateinit var dao: SearchCacheDao
    private lateinit var todoRepo: SqlDelightTodoRepository
    private lateinit var handler: TodoToolHandler

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MobileAgentDatabase.Schema.create(driver)
        db = MobileAgentDatabase(driver)
        dao = SearchCacheDao(db.searchCacheQueries, nowEpochMs = { 1_000L })
        todoRepo = SqlDelightTodoRepository(db.todosQueries, ioDispatcher = Dispatchers.Unconfined)
        handler = TodoToolHandler(todoRepo)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `add command runs deterministically and never invokes the engine`() = runTest {
        val session = RecordingSession()
        val loop = newLoop(session)

        val events = loop.run(AgentTurnInput("add buy milk to my todos")).toList()

        assertFalse("engine.generate must NOT be called on TODO turns", session.invoked)
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        // Synthetic turn chain: User → Assistant(toolCall) → Tool → Assistant(final)
        assertEquals(4, done.turnMessages.size)
        assertTrue(done.skipMemoryExtraction)
        // The repository observed the write.
        val saved = todoRepo.snapshot()
        assertEquals(1, saved.size)
        assertEquals("buy milk", saved.single().title)
    }

    @Test
    fun `intent without parse match emits TODO_GUIDANCE_TEXT and skips engine`() = runTest {
        val session = RecordingSession()
        val loop = newLoop(session)

        val events = loop.run(AgentTurnInput("do something with my todos")).toList()

        assertFalse(session.invoked)
        val text = events.filterIsInstance<AgentEvent.TokenChunk>().joinToString("") { it.text }
        assertEquals(AgentLoop.TODO_GUIDANCE_TEXT, text)
    }

    @Test
    fun `non-todo message falls through to the engine`() = runTest {
        val session = RecordingSession()
        val loop = newLoop(session)

        loop.run(AgentTurnInput("what's the weather in Toronto")).toList()

        assertTrue("engine.generate must be invoked for non-todo turns", session.invoked)
    }

    @Test
    fun `list_todos via chat returns the repository contents`() = runTest {
        // Seed two rows directly so the deterministic list path has something
        // to report.
        todoRepo.create(
            id = "t1",
            title = "buy milk",
            priority = com.contextsolutions.mobileagent.todo.TodoPriority.HIGH,
            dueDateEpochMs = null,
            notes = null,
            nowEpochMs = 100L,
        )
        todoRepo.create(
            id = "t2",
            title = "call mom",
            priority = com.contextsolutions.mobileagent.todo.TodoPriority.LOW,
            dueDateEpochMs = null,
            notes = null,
            nowEpochMs = 100L,
        )

        val session = RecordingSession()
        val loop = newLoop(session)

        val events = loop.run(AgentTurnInput("list my todos")).toList()

        assertFalse(session.invoked)
        val text = events.filterIsInstance<AgentEvent.TokenChunk>().joinToString("") { it.text }
        // The formatter renders both titles. Exact format may evolve, but
        // both rows must appear.
        assertTrue("expected 'buy milk' in formatted reply, got: $text", text.contains("buy milk"))
        assertTrue("expected 'call mom' in formatted reply, got: $text", text.contains("call mom"))
    }

    @Test
    fun `clock keyword wins precedence over todo keyword on ambiguous turns`() = runTest {
        val session = RecordingSession()
        val loop = newLoop(session)

        // Both intents could plausibly fire; the clock branch is checked
        // first so the parser sees this as a clock guidance turn (clock
        // parser will fail, clock intent fires, clock guidance emitted).
        val events = loop.run(AgentTurnInput("set a timer for my todo list")).toList()

        assertFalse(session.invoked)
        val text = events.filterIsInstance<AgentEvent.TokenChunk>().joinToString("") { it.text }
        assertEquals(AgentLoop.CLOCK_GUIDANCE_TEXT, text)
    }

    // -- helpers --------------------------------------------------------------

    private fun newLoop(session: RecordingSession): AgentLoop {
        val service = SearchService(
            object : BraveKeyProvider { override fun currentKey(): String? = null },
            FakeBrave,
            dao,
        )
        val context = TimeContext(
            now = LocalDateTime(2026, 5, 15, 12, 0),
            timeZoneId = "UTC",
            timeZoneAbbreviation = "UTC",
            utcOffset = "+00:00",
        )
        val assembler = PromptAssembler(timeContextProvider = { context })
        return AgentLoop(
            session = session,
            assembler = assembler,
            searchService = service,
            preflightRouter = unavailablePreflightRouter(service, context),
            toolHandlers = listOf(handler),
        )
    }

    private fun unavailablePreflightRouter(
        service: SearchService,
        timeContext: TimeContext,
    ): PreflightRouter {
        val emptyVocab = Vocab(
            tokenToId = mapOf("[PAD]" to 0, "[UNK]" to 100, "[CLS]" to 101, "[SEP]" to 102),
            idToToken = mapOf(0 to "[PAD]", 100 to "[UNK]", 101 to "[CLS]", 102 to "[SEP]"),
        )
        val noEngine = object : ClassifierEngine {
            override val isLoaded: Boolean = false
            override suspend fun warmUp(): ClassifierAccelerator? = null
            override suspend fun classify(inputIds: LongArray, attentionMask: LongArray): ClassifierOutput? = null
            override suspend fun unload() = Unit
        }
        return PreflightRouter(
            engine = noEngine,
            tokenizer = WordPieceTokenizer(emptyVocab),
            rewriter = QueryRewriter { timeContext },
            configProvider = { PreflightConfig.DEFAULT },
            searchAvailableProvider = { service.isAvailable() },
            logger = {},
        )
    }

    /**
     * InferenceSession that records whether [generate] was invoked.
     * Returns a single text emit + Done if it ever gets called, so
     * non-todo control turns still complete.
     */
    private class RecordingSession : InferenceSession {
        var invoked: Boolean = false
        override fun generate(request: GenerationRequest, toolDispatcher: ToolDispatcher?): Flow<GenerationEvent> = flow {
            invoked = true
            emit(GenerationEvent.TokenChunk("ok", 0))
            emit(GenerationEvent.Done(1, FinishReason.END_OF_TURN))
        }
    }

    private object FakeBrave : BraveSearchClient {
        override suspend fun search(query: String, apiKey: String): BraveSearchResult =
            BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "stub")
    }
}
