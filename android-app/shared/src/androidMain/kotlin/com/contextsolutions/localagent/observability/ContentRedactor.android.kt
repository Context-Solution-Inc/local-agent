package com.contextsolutions.localagent.observability

/** Android (JVM): copy the source stack trace so Crashlytics groups by the real origin. */
internal actual fun Throwable.applyStackTraceFrom(source: Throwable) {
    stackTrace = source.stackTrace
}
