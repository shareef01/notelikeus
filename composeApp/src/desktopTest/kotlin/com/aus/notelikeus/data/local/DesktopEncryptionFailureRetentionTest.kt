package com.aus.notelikeus.data.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * F-10: a readable plaintext database that cannot be encrypted this launch must stay usable.
 *
 * It used to be quarantined, so the app started with an empty library over notes that were
 * perfectly readable. It must instead stay in place, open as plaintext for this launch, and be
 * encrypted on a later one.
 */
class DesktopEncryptionFailureRetentionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @After
    fun clearFault() {
        DesktopPlaintextDatabaseMigrator.beforeRekeyForTests = null
    }

    private fun plaintextDatabaseWithANote(): File {
        val dbFile = File(temp.root, "notelikeus_db")
        JdbcSQLiteDriver(passphrase = null).open(dbFile.absolutePath).use { connection ->
            connection.prepare("CREATE TABLE note (id INTEGER PRIMARY KEY NOT NULL, title TEXT NOT NULL)")
                .use { assertFalse(it.step()) }
            connection.prepare("INSERT INTO note (id, title) VALUES (7, 'keep me')")
                .use { assertFalse(it.step()) }
        }
        return dbFile
    }

    private fun titleOf(dbFile: File, key: ByteArray?): String =
        JdbcSQLiteDriver(key).open(dbFile.absolutePath).use { connection ->
            connection.prepare("SELECT title FROM note WHERE id = 7").use { stmt ->
                assertTrue(stmt.step())
                stmt.getText(0)
            }
        }

    private fun quarantinedFiles(): List<String> =
        temp.root.list().orEmpty().filter { it.contains(".quarantined-") }

    @Test
    fun `a failed encryption keeps the plaintext database readable in place`() {
        val dbFile = plaintextDatabaseWithANote()
        DesktopPlaintextDatabaseMigrator.beforeRekeyForTests = { throw IOException("disk full") }

        val outcome = DesktopPlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(dbFile, passphrase)

        assertEquals(DatabaseEncryptionOutcome.PLAINTEXT_RETAINED, outcome)
        assertEquals(emptyList<String>(), quarantinedFiles())
        assertFalse(File(temp.root, "notelikeus_db-encrypted-temp").exists())
        // Opened the way the DI module opens it for this outcome: as plaintext.
        assertEquals("keep me", titleOf(dbFile, key = null))
    }

    @Test
    fun `the next launch encrypts the database it kept`() {
        val dbFile = plaintextDatabaseWithANote()
        DesktopPlaintextDatabaseMigrator.beforeRekeyForTests = { throw IOException("disk full") }
        DesktopPlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(dbFile, passphrase)
        DesktopPlaintextDatabaseMigrator.beforeRekeyForTests = null

        val outcome = DesktopPlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(dbFile, passphrase)

        assertEquals(DatabaseEncryptionOutcome.ENCRYPTED, outcome)
        assertEquals("keep me", titleOf(dbFile, passphrase))
    }

    @Test
    fun `an interrupted swap that left only the backup is restored and migrated`() {
        val dbFile = plaintextDatabaseWithANote()
        // The state a crash between "original moved aside" and "copy moved in" leaves behind.
        assertTrue(dbFile.renameTo(File(temp.root, "notelikeus_db.pre-encrypt")))

        val outcome = DesktopPlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(dbFile, passphrase)

        assertEquals(DatabaseEncryptionOutcome.ENCRYPTED, outcome)
        assertEquals("keep me", titleOf(dbFile, passphrase))
    }

    @Test
    fun `a database that opens neither way is still quarantined`() {
        val dbFile = File(temp.root, "notelikeus_db")
        dbFile.writeBytes(ByteArray(8192) { 0x5a })

        val outcome = DesktopPlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(dbFile, passphrase)

        assertEquals(DatabaseEncryptionOutcome.QUARANTINED, outcome)
        assertEquals(1, quarantinedFiles().count { it.startsWith("notelikeus_db.quarantined-") })
    }
}
