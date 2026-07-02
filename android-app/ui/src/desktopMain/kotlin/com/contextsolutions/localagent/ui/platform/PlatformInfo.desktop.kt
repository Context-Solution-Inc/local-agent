package com.contextsolutions.localagent.ui.platform

import androidx.compose.runtime.Composable

actual val isDesktopPlatform: Boolean = true

actual val isIosPlatform: Boolean = false

@Composable
actual fun rememberIsLandscape(): Boolean = false
