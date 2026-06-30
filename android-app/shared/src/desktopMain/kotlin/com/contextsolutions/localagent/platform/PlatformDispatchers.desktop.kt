package com.contextsolutions.localagent.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Desktop (JVM): the JVM elastic IO pool. */
actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.IO
