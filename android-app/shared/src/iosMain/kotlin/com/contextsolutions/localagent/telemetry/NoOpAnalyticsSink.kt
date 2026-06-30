package com.contextsolutions.localagent.telemetry

/**
 * iOS no-op [AnalyticsSink] (PR #41). Telemetry egress is not wired on iOS this
 * milestone (no Firebase); events are dropped. Telemetry is opt-in and off by
 * default anyway, so the `TelemetryUploader` simply has nowhere to send.
 */
object NoOpAnalyticsSink : AnalyticsSink {
    override fun send(event: AnalyticsSink.AnalyticsEvent) {}
}

/**
 * iOS no-op [TelemetryFlusher] (PR #41). The recording side uses
 * [NoOpTelemetryCounters] (nothing to drain), so flush is a no-op. The JVM
 * `InMemoryTelemetryCounters` is not portable (ConcurrentHashMap/AtomicLong).
 */
object NoOpTelemetryFlusher : TelemetryFlusher {
    override suspend fun flush() {}
}
