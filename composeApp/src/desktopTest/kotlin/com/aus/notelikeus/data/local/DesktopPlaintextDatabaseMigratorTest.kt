package com.aus.notelikeus.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom

class DesktopPlaintextDatabaseMigratorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun migratesPlaintextAndOpensOnlyWithPassphrase() {
        val dbFile = File(temp.root, "notelikeus_db")
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }

        JdbcSQLiteDriver(passphrase = null).open(dbFile.absolutePath).use { connection ->
            connection.prepare(
                "CREATE TABLE note (id INTEGER PRIMARY KEY NOT NULL, title TEXT NOT NULL)",
            ).use { assertFalse(it.step()) }
            connection.prepare("INSERT INTO note (id, title) VALUES (?, ?)").use { stmt ->
                stmt.bindLong(1, 7L)
                stmt.bindText(2, "secret")
                assertFalse(stmt.step())
            }
        }
        assertTrue(dbFile.exists())

        DesktopPlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(dbFile, passphrase)

        assertTrue(dbFile.exists())
        assertFalse(File(temp.root, "notelikeus_db.pre-encrypt").exists())
        assertFalse(File(temp.root, "notelikeus_db-encrypted-temp").exists())

        // Wrong / missing key must fail.
        var plaintextOpened = false
        try {
            JdbcSQLiteDriver(passphrase = null).open(dbFile.absolutePath).use { connection ->
                connection.prepare("SELECT title FROM note").use { stmt ->
                    plaintextOpened = stmt.step()
                }
            }
        } catch (_: Exception) {
            plaintextOpened = false
        }
        assertFalse(plaintextOpened)

        JdbcSQLiteDriver(passphrase).open(dbFile.absolutePath).use { connection ->
            connection.prepare("SELECT id, title FROM note").use { stmt ->
                assertTrue(stmt.step())
                assertEquals(7L, stmt.getLong(0))
                assertEquals("secret", stmt.getText(1))
            }
        }
    }

    @Test
    fun alreadyEncryptedIsIdempotent() {
        val dbFile = File(temp.root, "already.db")
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }

        JdbcSQLiteDriver(passphrase).open(dbFile.absolutePath).use { connection ->
            connection.prepare("CREATE TABLE t (id INTEGER PRIMARY KEY NOT NULL)").use {
                assertFalse(it.step())
            }
            connection.prepare("INSERT INTO t (id) VALUES (?)").use {
                it.bindLong(1, 1L)
                assertFalse(it.step())
            }
        }

        DesktopPlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(dbFile, passphrase)
        DesktopPlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(dbFile, passphrase)

        JdbcSQLiteDriver(passphrase).open(dbFile.absolutePath).use { connection ->
            connection.prepare("SELECT id FROM t").use { stmt ->
                assertTrue(stmt.step())
                assertEquals(1L, stmt.getLong(0))
            }
        }
    }
}
