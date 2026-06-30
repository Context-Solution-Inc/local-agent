package com.contextsolutions.localagent.ui.settings

import androidx.compose.runtime.Composable

/** iOS no-op (PR #41): the GPU device pin is a desktop llama-server concern only. */
@Composable
actual fun DesktopGpuSection() = Unit
