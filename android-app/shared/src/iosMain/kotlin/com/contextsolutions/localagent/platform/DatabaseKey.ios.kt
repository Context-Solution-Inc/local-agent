package com.contextsolutions.localagent.platform

/** iOS datastore encryption is Phase 2 (the iOS DB driver is unkeyed for now). */
actual fun generateDatabaseKey(): String =
    throw NotImplementedError("Database encryption key generation is iOS Phase 2")
