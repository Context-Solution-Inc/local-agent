package com.contextsolutions.mobileagent.app.di

import android.util.Log
import com.contextsolutions.mobileagent.app.BuildConfig
import com.contextsolutions.mobileagent.db.SearchCacheQueries
import com.contextsolutions.mobileagent.platform.AgentClock
import com.contextsolutions.mobileagent.platform.HttpEngineFactory
import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import com.contextsolutions.mobileagent.search.BraveKeyProvider
import com.contextsolutions.mobileagent.search.BraveSearchClient
import com.contextsolutions.mobileagent.search.DefaultBraveKeyProvider
import com.contextsolutions.mobileagent.search.KtorBraveLlmContextClient
import com.contextsolutions.mobileagent.search.KtorBraveSearchClient
import com.contextsolutions.mobileagent.search.SearchCacheDao
import com.contextsolutions.mobileagent.search.SearchService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Wires the search layer's commonMain seams into Hilt: key resolution, the Brave
 * HTTP client, the SQLite-backed cache, and the [SearchService] that composes
 * them. The dev key fallback is only honoured on internal builds; release
 * builds always pass `null`, so production users must enter their own key
 * (PRD §3.6 BYOK).
 *
 * The Ktor [HttpClient] backing the Brave client is constructed inside the
 * provider rather than published as its own binding — Brave is the only HTTP
 * caller in the agent (model download uses a separate OkHttpClient) and
 * keeping Ktor types out of `:androidApp` keeps the Hilt graph small.
 */
/**
 * Marks the [SearchService] backed by Brave's LLM Context endpoint
 * (`/llm/context`) rather than `/web/search`. Only SPORTS consumes it (PR #41).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SportsSearch

@Module
@InstallIn(SingletonComponent::class)
object SearchModule {

    private const val TAG = "BraveApi"

    @Provides
    @Singleton
    fun provideBraveKeyProvider(secureStorage: SecureStorage): BraveKeyProvider {
        val devKey = if (BuildConfig.INTERNAL_BUILD) BuildConfig.BRAVE_DEV_KEY else null
        return DefaultBraveKeyProvider(secureStorage, devKey)
    }

    @Provides
    @Singleton
    fun provideBraveSearchClient(httpEngineFactory: HttpEngineFactory): BraveSearchClient =
        KtorBraveSearchClient(httpEngineFactory) { Log.i(TAG, it) }

    @Provides
    @Singleton
    fun provideSearchCacheDao(
        queries: SearchCacheQueries,
        clock: AgentClock,
    ): SearchCacheDao = SearchCacheDao(queries = queries, nowEpochMs = clock::nowEpochMs)

    @Provides
    @Singleton
    fun provideSearchService(
        keyProvider: BraveKeyProvider,
        client: BraveSearchClient,
        cache: SearchCacheDao,
        secureStorage: SecureStorage,
        counters: com.contextsolutions.mobileagent.telemetry.TelemetryCounters,
    ): SearchService = SearchService(
        keyProvider = keyProvider,
        client = client,
        cache = cache,
        // Default ON; only the explicit string "false" disables. UI writes
        // "true"/"false" via SettingsViewModel.
        isEnabled = { secureStorage.get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
        counters = counters,
    )

    /**
     * SPORTS-only [SearchService] backed by Brave's LLM Context endpoint
     * (PR #41). Shares the same key provider, cache DAO, enable-toggle, and
     * counters as the web-search service — only the underlying client (and thus
     * the endpoint + response shape) differs. The Ktor [io.ktor.client.HttpClient]
     * is built inside [KtorBraveLlmContextClient] so this module stays Ktor-free,
     * mirroring [provideBraveSearchClient].
     */
    @Provides
    @Singleton
    @SportsSearch
    fun provideSportsSearchService(
        httpEngineFactory: HttpEngineFactory,
        keyProvider: BraveKeyProvider,
        cache: SearchCacheDao,
        secureStorage: SecureStorage,
        counters: com.contextsolutions.mobileagent.telemetry.TelemetryCounters,
    ): SearchService = SearchService(
        keyProvider = keyProvider,
        client = KtorBraveLlmContextClient(httpEngineFactory) { Log.i(TAG, it) },
        cache = cache,
        isEnabled = { secureStorage.get(SecureStorageKeys.SEARCH_ENABLED) != "false" },
        counters = counters,
        // Namespace the shared cache so an unpinned SPORTS query can't collide
        // with an identical GENERAL query's `/web/search` payload (PR #41).
        cacheNamespace = "sports:",
    )
}
