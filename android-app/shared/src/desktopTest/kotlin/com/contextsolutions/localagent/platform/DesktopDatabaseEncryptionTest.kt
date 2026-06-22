package com.contextsolutions.localagent.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.db.LocalAgentDatabase
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M1 — proves the desktop SQLDelight DB is genuinely SQLCipher-encrypted at rest, survives a
 * reopen with the stored key, is unreadable with the wrong key, wipes a legacy plaintext DB,
 * and refuses to open an encrypted DB whose key was lost. Uses a real temp-dir file (not
 * IN_MEMORY) so the on-disk bytes can be inspected.
 */
class DesktopDatabaseEncryptionTest {
    private val baseDir: File = Files.createTempDirectory("m1-enc-test").toFile()
    private val dbFile: File get() = File(baseDir, DesktopDatabaseFactory.DB_FILENAME)

    @AfterTest
    fun cleanup() {
        baseDir.deleteRecursively()
    }

    @Test
    fun mints_key_encrypts_and_round_trips_across_reopen() {
        val secure = FakeSecureStorage()

        DesktopDatabaseFactory.create(secure, baseDir).use { driver ->
            insertMemory(LocalAgentDatabase(driver), "m1")
        }
        // First run minted + persisted the passphrase.
        assertNotNull(secure.get(SecureStorageKeys.DB_ENCRYPTION_KEY))
        // On disk it is NOT a plaintext SQLite file.
        assertFalse(SqliteHeader.isPlaintext(firstBytes(dbFile)))

        // Reopen with the same secure store → same key → the row survives.
        DesktopDatabaseFactory.create(secure, baseDir).use { driver ->
            assertEquals(1L, countMemories(LocalAgentDatabase(driver)))
        }
    }

    @Test
    fun wrong_key_cannot_read_the_database() {
        val secure = FakeSecureStorage()
        DesktopDatabaseFactory.create(secure, baseDir).use { driver ->
            insertMemory(LocalAgentDatabase(driver), "m1")
        }

        val wrongProps = SQLiteMCSqlCipherConfig.getDefault().withKey("not-the-key").build().toProperties()
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", wrongProps).use { driver ->
            assertFailsWith<Exception> { readUserVersion(driver) }
        }
    }

    @Test
    fun wipes_legacy_plaintext_db_then_recreates_encrypted() {
        // Stand up a pre-M1 plaintext DB (willena with no key writes plaintext sqlite3mc).
        baseDir.mkdirs()
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties()).use { driver ->
            LocalAgentDatabase.Schema.create(driver)
            insertMemory(LocalAgentDatabase(driver), "legacy")
        }
        assertTrue(SqliteHeader.isPlaintext(firstBytes(dbFile)))

        // Opening via the factory wipes it (fresh-start) and builds an empty encrypted DB.
        val secure = FakeSecureStorage()
        DesktopDatabaseFactory.create(secure, baseDir).use { driver ->
            assertEquals(0L, countMemories(LocalAgentDatabase(driver)))
        }
        assertFalse(SqliteHeader.isPlaintext(firstBytes(dbFile)))
    }

    @Test
    fun refuses_to_open_encrypted_db_when_key_is_lost() {
        val secure = FakeSecureStorage()
        DesktopDatabaseFactory.create(secure, baseDir).use { driver ->
            insertMemory(LocalAgentDatabase(driver), "m1")
        }
        // Secure store lost the key but the encrypted file remains → unrecoverable; must fail loud.
        secure.remove(SecureStorageKeys.DB_ENCRYPTION_KEY)
        assertFailsWith<IllegalStateException> { DesktopDatabaseFactory.create(secure, baseDir) }
    }

    @Test
    fun key_provider_is_idempotent() {
        val secure = FakeSecureStorage()
        val first = DatabaseKeyProvider.getOrCreate(secure)
        val second = DatabaseKeyProvider.getOrCreate(secure)
        assertEquals(first, second)
        assertEquals(first, secure.get(SecureStorageKeys.DB_ENCRYPTION_KEY))
    }

    private fun insertMemory(db: LocalAgentDatabase, id: String) {
        db.memoriesQueries.insertMemory(
            id = id,
            text = "secret pii for $id",
            category = "preference",
            conversation_id = null,
            created_at_epoch_ms = 1_000L,
            last_accessed_epoch_ms = 1_000L,
            access_count = 0L,
            embedding = ByteArray(8) { it.toByte() },
            expires_at_epoch_ms = null,
            updated_at_epoch_ms = 1_000L,
        )
    }

    private fun countMemories(db: LocalAgentDatabase): Long =
        db.memoriesQueries.selectAllMemories().executeAsList().size.toLong()

    private fun firstBytes(file: File): ByteArray =
        file.inputStream().use { stream ->
            val buf = ByteArray(SqliteHeader.MAGIC_LENGTH)
            stream.read(buf)
            buf
        }

    private fun readUserVersion(driver: JdbcSqliteDriver): Long =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            parameters = 0,
        ).value ?: 0L

    private class FakeSecureStorage : SecureStorage {
        private val map = HashMap<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
    }
}
