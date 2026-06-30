package com.contextsolutions.localagent.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalWindowInfo

/** iOS is a mobile platform, not desktop (PR #41). */
actual val isDesktopPlatform: Boolean = false

/**
 * iOS actual (PR #41): landscape when the container is wider than tall. Drives the
 * same alarm-dialog widening as Android (invariant — see [rememberIsLandscape]).
 */
@Composable
actual fun rememberIsLandscape(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    return size.width > size.height
}
