package com.contextsolutions.localagent.inference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * iOS [SystemMemoryStatusProvider] (PR #41) — a constant healthy state, like the
 * desktop provider (no portable per-process RAM-pressure signal mapping to the
 * Pixel-7 LMKD bands). The chat header dot stays green.
 */
class IosSystemMemoryStatusProvider : SystemMemoryStatusProvider {
    override val status: StateFlow<MemoryStatus> = MutableStateFlow(MemoryStatus.Green)
}
