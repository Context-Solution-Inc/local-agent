package com.contextsolutions.localagent.ui.platform

import androidx.compose.runtime.Composable

/**
 * True on the desktop (Compose Desktop) app, false on Android (PR #57). Used by
 * shared `:ui` screens to vary platform-specific UI without a separate screen —
 * e.g. the desktop hosts the link (shows a QR + connected phone instead of the
 * mobile pairing controls) and hides the mobile-only my-list/timer/alarm entries.
 */
expect val isDesktopPlatform: Boolean

/**
 * True only on iOS (PR #46). The mobile chat-scroll branch (`!isDesktopPlatform`) is shared
 * by Android AND iOS, so `isDesktopPlatform` can't isolate an iOS-only tweak. This flag gates
 * the iOS scroll-hardening changes (drag-based sticky disengage, token coalescing) so the
 * proven Android/desktop paths stay byte-identical while iOS tolerates its CMP-runtime quirks.
 */
expect val isIosPlatform: Boolean

/**
 * True when the device is currently in landscape orientation (Android). Desktop
 * always returns false — it has no portrait/landscape notion and keeps its sizing.
 * Used to widen the mobile alarm dialog in landscape so the horizontal `TimePicker`
 * layout doesn't overlap the number/AM-PM column (PR #71).
 */
@Composable
expect fun rememberIsLandscape(): Boolean
