package com.contextsolutions.localagent.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqliteHeaderTest {
    @Test
    fun recognizes_the_plaintext_sqlite_magic() {
        val magic = "SQLite format 3".encodeToByteArray() + 0x00.toByte()
        assertTrue(SqliteHeader.isPlaintext(magic))
        // Trailing bytes after the 16-byte magic are ignored.
        assertTrue(SqliteHeader.isPlaintext(magic + ByteArray(100)))
    }

    @Test
    fun rejects_encrypted_or_short_headers() {
        assertFalse(SqliteHeader.isPlaintext(ByteArray(SqliteHeader.MAGIC_LENGTH) { 0x7F }))
        assertFalse(SqliteHeader.isPlaintext(ByteArray(4)))
        assertFalse(SqliteHeader.isPlaintext(ByteArray(0)))
        // A space instead of the trailing NUL must not match.
        assertFalse(SqliteHeader.isPlaintext("SQLite format 3 ".encodeToByteArray()))
    }
}
