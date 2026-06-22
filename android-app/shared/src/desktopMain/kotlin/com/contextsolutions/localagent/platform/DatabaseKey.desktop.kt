package com.contextsolutions.localagent.platform

/**
 * Reuses the single keystore-grade generator ([KeystorePassword.generateRandomPassword]):
 * 32 secure-random bytes → URL-safe Base64. Returned as a String for the SQLCipher passphrase.
 */
actual fun generateDatabaseKey(): String = String(KeystorePassword.generateRandomPassword())
