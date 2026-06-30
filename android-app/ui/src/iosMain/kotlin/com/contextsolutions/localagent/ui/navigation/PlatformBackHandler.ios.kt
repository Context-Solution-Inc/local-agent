package com.contextsolutions.localagent.ui.navigation

import androidx.compose.runtime.Composable

/**
 * iOS actual (PR #41): no-op. In-app navigation is driven by the explicit
 * on-screen back affordances; the system swipe-back gesture is a later
 * refinement and does not change this contract.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Intentionally empty — no ambient platform back event wired on iOS yet.
}
