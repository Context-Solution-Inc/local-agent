package com.contextsolutions.localagent.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * iOS [HttpEngineFactory] (PR #41) — Ktor's Darwin engine (NSURLSession), the
 * iOS counterpart of [AndroidHttpEngineFactory] (OkHttp) / [DesktopHttpEngineFactory]
 * (CIO). Same JSON content-negotiation + timeouts so the Ollama/search/link clients
 * behave identically. Per-request logging is off (no query strings / keys in logs,
 * PRD §4.4).
 */
class IosHttpEngineFactory : HttpEngineFactory {
    override fun create(block: HttpClientConfig<*>.() -> Unit): HttpClient =
        HttpClient(Darwin) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
            block()
        }
}
