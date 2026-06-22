package com.contextsolutions.localagent.platform

/**
 * Desktop diagnostic-logging gate (security/privacy).
 *
 * The desktop build wires ~35 component loggers (AgentLoop turn dumps, the full Ollama
 * request body, the pre-flight classifier query, ktor request/response, memory extraction,
 * …) straight to `System.err`. In a **production packaged build** these are noise and a
 * privacy leak — they echo the user's prompt + search query. So every diagnostic logger
 * routes through [log], which prints **only when [verbose]**.
 *
 * [verbose] follows the same signal as [AppBuildConfig.isDebug] on desktop — the
 * `localagent.debug` system property (plus `isInternalBuild`, always false here). A
 * normal packaged launch leaves it false → quiet. A developer keeps full logging by
 * running with `-Dlocalagent.debug=true` (the Gradle `:desktopApp:run` task sets it
 * automatically). Operational errors/warnings are logged directly via `System.err` and
 * are NOT gated by this.
 */
object DesktopDiag {
    val verbose: Boolean = DesktopAppBuildConfig().let { it.isDebug || it.isInternalBuild }

    /** Emit a diagnostic line to stderr, but only on a debug/internal run. */
    fun log(line: String) {
        if (verbose) System.err.println(line)
    }
}
