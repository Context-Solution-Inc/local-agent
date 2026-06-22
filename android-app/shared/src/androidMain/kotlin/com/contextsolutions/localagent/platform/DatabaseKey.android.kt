package com.contextsolutions.localagent.platform

import java.security.SecureRandom
import java.util.Base64

/** 32 secure-random bytes → URL-safe Base64 (ASCII, ~43 chars). Mirrors the desktop generator. */
actual fun generateDatabaseKey(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
