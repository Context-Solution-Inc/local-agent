package com.contextsolutions.localagent.ui.settings

import androidx.compose.runtime.Composable

/** iOS no-op (PR #41): no in-app read-aloud voice picker on iOS (matches Android). */
@Composable
actual fun DesktopVoiceSection() = Unit
