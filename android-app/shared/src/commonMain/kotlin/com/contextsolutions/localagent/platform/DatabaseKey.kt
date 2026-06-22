package com.contextsolutions.localagent.platform

/**
 * Mints a fresh ~256-bit database passphrase for SQLCipher (M1 — encryption at rest).
 *
 * Both JVM targets back this with `SecureRandom` + URL-safe Base64 (the same generator the
 * desktop keystore password uses, [com.contextsolutions.localagent.platform.KeystorePassword]).
 * iOS DB encryption is Phase 2, so its actual throws.
 */
expect fun generateDatabaseKey(): String

/**
 * Owns the lifecycle of the SQLCipher passphrase ([SecureStorageKeys.DB_ENCRYPTION_KEY]).
 *
 * The key is generated once on first run and persisted in [SecureStorage] (Android
 * Keystore-backed EncryptedSharedPreferences / desktop PKCS#12 `secrets.p12`); thereafter it
 * is read back to open the keyed driver. The platform DB factories (`DesktopDatabaseFactory`,
 * the Android Koin binding) call [getOrCreate] when building the driver and use [existing] for
 * the keystore-loss guard (an encrypted DB on disk with no key means the secure store was lost,
 * never a first run — fail loudly instead of orphaning the data with a fresh key).
 */
object DatabaseKeyProvider {
    /** The stored passphrase, generating + persisting one on first run. Never logged. */
    fun getOrCreate(secure: SecureStorage): String {
        secure.get(SecureStorageKeys.DB_ENCRYPTION_KEY)?.let { return it }
        val key = generateDatabaseKey()
        secure.put(SecureStorageKeys.DB_ENCRYPTION_KEY, key)
        return key
    }

    /** The stored passphrase, or null if none has been minted yet (i.e. a genuine first run). */
    fun existing(secure: SecureStorage): String? = secure.get(SecureStorageKeys.DB_ENCRYPTION_KEY)
}

/**
 * The 16-byte magic every plaintext SQLite file starts with. A SQLCipher-encrypted database
 * encrypts page 1 including this header, so an encrypted file never carries it — making the
 * header a race-free way to tell a legacy plaintext `local_agent.db` from an encrypted one
 * (M1 fresh-start wipe + keystore-loss guard).
 */
object SqliteHeader {
    /** Length of the magic to read off the front of a candidate DB file. */
    const val MAGIC_LENGTH: Int = 16

    // "SQLite format 3" (15 ASCII bytes) + a trailing NUL = the 16-byte magic.
    private val PLAINTEXT_MAGIC: ByteArray = "SQLite format 3".encodeToByteArray() + 0x00.toByte()

    /** True when [headerBytes] (the first [MAGIC_LENGTH] bytes of a file) is the plaintext magic. */
    fun isPlaintext(headerBytes: ByteArray): Boolean =
        headerBytes.size >= MAGIC_LENGTH &&
            headerBytes.copyOfRange(0, MAGIC_LENGTH).contentEquals(PLAINTEXT_MAGIC)
}
