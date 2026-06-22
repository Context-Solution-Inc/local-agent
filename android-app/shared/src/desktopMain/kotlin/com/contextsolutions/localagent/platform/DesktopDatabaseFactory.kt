package com.contextsolutions.localagent.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.db.LocalAgentDatabase
import com.contextsolutions.localagent.inference.DesktopAppDirs
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import java.io.File

/**
 * File-backed SQLDelight driver for desktop (Phase 6, docs/DESKTOP_PORT_PLAN.md).
 * Replaces the Phase-2 in-memory `JdbcSqliteDriver(IN_MEMORY)` so conversations,
 * memories, the search cache, clock/my-list and telemetry survive across launches —
 * the desktop counterpart of Android's `AndroidSqliteDriver` over `local_agent.db`.
 *
 * SQLDelight 2.0.2's `JdbcSqliteDriver` has no schema-aware constructor, so we
 * drive create/migrate off SQLite's `PRAGMA user_version` ourselves (what
 * `AndroidSqliteDriver` does internally via its `SupportSQLiteOpenHelper`):
 *  - fresh file (version 0) → `Schema.create` + stamp `Schema.version`;
 *  - older version → `Schema.migrate` (walks the committed `.sqm` files) + restamp;
 *  - current → open as-is.
 *
 * The DB lives at `<app-data>/local_agent.db` ([DesktopAppDirs]); the same file
 * the Phase-7 tray/UI and any background task queue read.
 *
 * M1 — the connection is keyed with SQLCipher (via the willena/sqlite3mc driver). The
 * passphrase comes from [SecureStorage] ([DatabaseKeyProvider]); it is set through the JDBC
 * [java.util.Properties] so EVERY connection the driver opens is keyed (a one-shot `PRAGMA key`
 * would leave any later connection unkeyed). On open we also do a one-time fresh-start wipe of
 * any legacy plaintext `local_agent.db` (pre-M1 installs), and fail loudly if an encrypted DB
 * exists but the key is gone (secure-store loss — never silently re-key a fresh DB over it).
 */
object DesktopDatabaseFactory {
    const val DB_FILENAME: String = "local_agent.db"

    fun create(
        secureStorage: SecureStorage,
        baseDir: File = DesktopAppDirs.dataDir(),
        logger: (String) -> Unit = {},
    ): SqlDriver {
        baseDir.mkdirs()
        val dbFile = File(baseDir, DB_FILENAME)

        // Fresh-start wipe: a pre-M1 plaintext DB carries the SQLite magic header; an encrypted
        // one never does. Delete it (+ WAL/SHM sidecars) so the create path builds an encrypted DB.
        if (dbFile.exists() && isPlaintextDb(dbFile)) {
            deleteDbFiles(dbFile)
            logger("wiped legacy plaintext DB at $dbFile (M1 fresh-start)")
        }
        // Keystore-loss guard: an encrypted DB remains but the key is gone → unrecoverable; do
        // not regenerate a key (that would orphan the data behind a fresh, mismatched DB).
        if (dbFile.exists() && DatabaseKeyProvider.existing(secureStorage) == null) {
            error("Encrypted database present but no key in secure storage — secure store lost; refusing to re-key")
        }

        val passphrase = DatabaseKeyProvider.getOrCreate(secureStorage)
        val props = SQLiteMCSqlCipherConfig.getDefault().withKey(passphrase).build().toProperties()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", props)
        val schema = LocalAgentDatabase.Schema
        when (val current = userVersion(driver)) {
            0L -> {
                schema.create(driver)
                setUserVersion(driver, schema.version)
                logger("created schema v${schema.version} at $dbFile")
            }
            in 1 until schema.version -> {
                schema.migrate(driver, current, schema.version)
                setUserVersion(driver, schema.version)
                logger("migrated schema $current → ${schema.version} at $dbFile")
            }
            else -> logger("opened schema v$current at $dbFile")
        }
        return driver
    }

    private fun userVersion(driver: SqlDriver): Long =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            parameters = 0,
        ).value ?: 0L

    private fun setUserVersion(driver: SqlDriver, version: Long) {
        // PRAGMA doesn't accept bound parameters; version is a trusted Long.
        driver.execute(identifier = null, sql = "PRAGMA user_version = $version", parameters = 0)
    }

    /** True when [dbFile] starts with the plaintext SQLite magic (i.e. an unencrypted legacy DB). */
    private fun isPlaintextDb(dbFile: File): Boolean {
        val header = ByteArray(SqliteHeader.MAGIC_LENGTH)
        val read = dbFile.inputStream().use { it.read(header) }
        return read == SqliteHeader.MAGIC_LENGTH && SqliteHeader.isPlaintext(header)
    }

    /** Deletes the DB and its WAL/SHM sidecars (best-effort). */
    private fun deleteDbFiles(dbFile: File) {
        listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).forEach { it.delete() }
    }
}
