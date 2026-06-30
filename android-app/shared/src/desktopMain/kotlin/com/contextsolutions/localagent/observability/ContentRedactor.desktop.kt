package com.contextsolutions.localagent.observability

/** Desktop (JVM): copy the source stack trace (matches the Android actual). */
internal actual fun Throwable.applyStackTraceFrom(source: Throwable) {
    stackTrace = source.stackTrace
}
