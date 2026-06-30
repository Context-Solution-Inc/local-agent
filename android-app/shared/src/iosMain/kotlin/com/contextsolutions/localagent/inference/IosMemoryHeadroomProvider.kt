package com.contextsolutions.localagent.inference

import platform.Foundation.NSProcessInfo

/**
 * iOS [MemoryHeadroomProvider] (PR #41). There is no public "free system RAM" API
 * on iOS, so this reports the device's total physical memory as a coarse upper
 * bound — enough for the warm-model gate to never spuriously refuse a load (the
 * on-device LLM's own load failure is the real backstop). A jetsam-aware estimate
 * is a follow-up.
 */
class IosMemoryHeadroomProvider : MemoryHeadroomProvider {
    override fun availableBytes(): Long =
        NSProcessInfo.processInfo.physicalMemory.toLong()
}
