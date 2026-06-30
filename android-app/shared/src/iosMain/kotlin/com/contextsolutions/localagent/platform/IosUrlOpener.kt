package com.contextsolutions.localagent.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS [UrlOpener] (PR #41) — opens a URL in Safari via `UIApplication.openURL`.
 * Best-effort: a malformed URL is ignored.
 */
class IosUrlOpener : UrlOpener {
    override fun openUrl(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(nsUrl)
    }
}
