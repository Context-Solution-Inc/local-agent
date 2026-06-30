package com.contextsolutions.localagent.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * iOS [ThermalStatusProvider] (PR #41) — a constant [ThermalStatus.NONE] stub, like
 * the desktop provider. iOS exposes `NSProcessInfo.thermalState`; wiring it (and
 * mapping NOMINAL/FAIR/SERIOUS/CRITICAL onto these bands) is a follow-up. Reporting
 * NONE means no thermal gate ever fires.
 */
class IosThermalStatusProvider : ThermalStatusProvider {
    override fun current(): ThermalStatus = ThermalStatus.NONE
    override fun statusFlow(): Flow<ThermalStatus> = flowOf(ThermalStatus.NONE)
}
