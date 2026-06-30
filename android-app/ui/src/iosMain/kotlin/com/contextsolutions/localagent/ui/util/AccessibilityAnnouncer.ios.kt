package com.contextsolutions.localagent.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * iOS actual (PR #41): no-op for this milestone. A VoiceOver announcement via
 * `UIAccessibility.post(notification:)` is a follow-up.
 */
@Composable
actual fun rememberAccessibilityAnnouncer(): AccessibilityAnnouncer =
    remember { AccessibilityAnnouncer { } }
