package com.contextsolutions.localagent.app

import android.util.Log

/**
 * App-wide diagnostic-logging gate for `:androidApp` lifecycle classes that log directly
 * via `Log.i` (memory monitor/watchdog, telemetry worker, warm-up) rather than through a
 * DI-injected logger. Mirrors `AndroidKoinModule.diagLog` and the desktop `DesktopDiag`:
 * informational lines print only on a debug/internal build, so a RELEASE build stays quiet.
 *
 * Use [i] for diagnostic/lifecycle info. Genuine errors/warnings should keep using
 * `Log.w`/`Log.e` directly — those are NOT gated.
 */
object AppDiag {
    val verbose: Boolean = BuildConfig.DEBUG || BuildConfig.INTERNAL_BUILD

    /** Log a diagnostic line at INFO, but only on a debug/internal build. */
    fun i(tag: String, message: String) {
        if (verbose) Log.i(tag, message)
    }
}
