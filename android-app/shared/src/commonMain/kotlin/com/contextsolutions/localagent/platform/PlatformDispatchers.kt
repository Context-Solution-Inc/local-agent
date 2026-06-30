package com.contextsolutions.localagent.platform

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher for blocking IO (SQLDelight queries, HTTP, file work). On the JVM
 * targets this is `Dispatchers.IO` (an elastic pool sized for blocking work); on
 * Kotlin/Native (iOS) `Dispatchers.IO` is internal/unavailable, so the actual is
 * `Dispatchers.Default` (PR #41). Repositories/engines default their injectable
 * `ioDispatcher` param to this instead of referencing `Dispatchers.IO` directly,
 * so commonMain compiles for all targets while JVM behaviour is unchanged.
 */
expect val platformIoDispatcher: CoroutineDispatcher
