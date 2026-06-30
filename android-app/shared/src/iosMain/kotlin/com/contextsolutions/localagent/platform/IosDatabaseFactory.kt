package com.contextsolutions.localagent.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.contextsolutions.localagent.db.LocalAgentDatabase

/**
 * iOS SQLDelight driver (PR #41) — the [NativeSqliteDriver] over `local_agent.db`
 * in the app sandbox. The native driver drives `Schema.create`/`migrate` itself
 * (unlike the desktop `JdbcSqliteDriver`), so this is just construction.
 *
 * **Unkeyed for this milestone.** SQLCipher-on-iOS is deferred (the desktop/Android
 * factories key via [DatabaseKeyProvider]; [generateDatabaseKey] still throws on iOS
 * and is never called here). DB encryption at rest on iOS is a follow-up.
 */
object IosDatabaseFactory {
    const val DB_FILENAME: String = "local_agent.db"

    fun create(): SqlDriver =
        NativeSqliteDriver(LocalAgentDatabase.Schema, DB_FILENAME)
}
