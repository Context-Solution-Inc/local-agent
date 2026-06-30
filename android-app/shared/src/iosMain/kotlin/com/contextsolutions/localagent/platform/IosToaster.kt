package com.contextsolutions.localagent.platform

/**
 * iOS [Toaster] (PR #41) — logs to stdout for now. A richer transient surface
 * (a Compose snackbar) can replace this behind the same interface later.
 */
class IosToaster : Toaster {
    override fun show(message: String) {
        println("[toast] $message")
    }
}
