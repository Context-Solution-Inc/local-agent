package com.contextsolutions.localagent.ui.settings

import androidx.compose.runtime.Composable

/**
 * iOS no-op (PR #41): relay/desktop-pairing is deferred on iOS (no Secure Gateway
 * iOS artifact yet), so the link section renders no pairing controls. The desktop
 * link status is DISABLED on iOS, so this is not normally reached.
 */
@Composable
actual fun DesktopLinkPairingControls(
    state: SettingsUiState,
    onScanned: (String) -> Unit,
    onUnpair: () -> Unit,
    onPairNow: () -> Unit,
) = Unit
