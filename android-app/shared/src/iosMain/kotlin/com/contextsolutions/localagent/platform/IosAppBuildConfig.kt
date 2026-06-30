package com.contextsolutions.localagent.platform

import kotlin.experimental.ExperimentalNativeApi
import platform.Foundation.NSBundle

/**
 * iOS [AppBuildConfig] (PR #41). Ships no bundled dev secrets (keys are entered in
 * Settings → SecureStorage/Keychain), so the dev-key flags are false. Version /
 * build read from the app bundle's Info.plist; `isDebug` follows the Kotlin/Native
 * debug-binary flag.
 */
class IosAppBuildConfig : AppBuildConfig {
    @OptIn(ExperimentalNativeApi::class)
    override val isDebug: Boolean = Platform.isDebugBinary
    override val isInternalBuild: Boolean = false
    override val hasBraveDevKey: Boolean = false

    private fun infoString(key: String): String? =
        NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String

    override val versionName: String = infoString("CFBundleShortVersionString") ?: "1.0.0"
    override val versionCode: Int = infoString("CFBundleVersion")?.toIntOrNull() ?: 1
    override val gitDescribe: String = infoString("LocalAgentGitDescribe") ?: "ios"
}
