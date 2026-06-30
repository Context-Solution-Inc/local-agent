package com.contextsolutions.localagent.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * iOS (Kotlin/Native): `Dispatchers.IO` is internal on Native, so blocking work
 * runs on `Dispatchers.Default` (a thread pool sized to the device's cores). The
 * SQLDelight native driver + Ktor/Darwin tolerate this; the volume of concurrent
 * blocking IO on a phone is low (PR #41).
 */
actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.Default
