package com.contextsolutions.mobileagent.app.di

import com.contextsolutions.mobileagent.agent.TodoCommandParser
import com.contextsolutions.mobileagent.agent.TodoIntentDetector
import com.contextsolutions.mobileagent.agent.TodoResponseFormatter
import com.contextsolutions.mobileagent.agent.TodoToolHandler
import com.contextsolutions.mobileagent.db.TodosQueries
import com.contextsolutions.mobileagent.todo.SqlDelightTodoRepository
import com.contextsolutions.mobileagent.todo.TodoRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the PR #15 TODO feature.
 *
 * - [TodoRepository] is a singleton so the chat handler and the management
 *   screen ViewModel share the same backing StateFlow — a mutation from
 *   either side propagates to the other.
 * - The agent-layer helpers ([TodoIntentDetector], [TodoCommandParser],
 *   [TodoResponseFormatter]) are stateless and could be re-created per call
 *   without harm; keeping them as singletons matches [ClockToolHandler]'s
 *   approach and avoids per-turn allocations.
 * - [TodoToolHandler] is registered in [AgentModule.provideAgentLoopFactory]
 *   as the entry into the `toolHandlers` list.
 */
@Module
@InstallIn(SingletonComponent::class)
object TodoModule {

    @Provides
    @Singleton
    fun provideTodoRepository(queries: TodosQueries): TodoRepository =
        SqlDelightTodoRepository(queries)

    @Provides
    @Singleton
    fun provideTodoIntentDetector(): TodoIntentDetector = TodoIntentDetector()

    @Provides
    @Singleton
    fun provideTodoCommandParser(): TodoCommandParser = TodoCommandParser()

    @Provides
    @Singleton
    fun provideTodoResponseFormatter(): TodoResponseFormatter = TodoResponseFormatter()

    @Provides
    @Singleton
    fun provideTodoToolHandler(repository: TodoRepository): TodoToolHandler =
        TodoToolHandler(repository)
}
