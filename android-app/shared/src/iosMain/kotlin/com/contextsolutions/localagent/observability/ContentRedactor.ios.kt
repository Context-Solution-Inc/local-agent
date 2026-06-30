package com.contextsolutions.localagent.observability

/**
 * iOS (Kotlin/Native): `Throwable.stackTrace` is private on Native and cannot be
 * reassigned, so this is a no-op — the RedactedThrowable keeps its own native
 * stack. Crash reporting is a NoOp on iOS this milestone (PR #41).
 */
internal actual fun Throwable.applyStackTraceFrom(source: Throwable) {
    // No-op on Native.
}
