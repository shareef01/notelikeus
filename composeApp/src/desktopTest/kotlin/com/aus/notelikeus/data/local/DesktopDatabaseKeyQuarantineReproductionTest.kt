package com.aus.notelikeus.data.local

import com.aus.notelikeus.data.attachments.IdentitySecureBlobStore
import com.aus.notelikeus.data.attachments.SecureBlobStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopDatabaseKeyQuarantineReproductionTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun simulateTwoLaunchFailureSequence() {
        val rootDir = temp.newFolder("profile")
        val keyDir = File(rootDir, ".notelikeus").apply { mkdirs() }
        val dbFile = File(rootDir, "notes.db")

        // 0. INITIAL STATE:
        // Set up normal blob store and mint a valid initial passphrase
        val normalBlobStore = IdentitySecureBlobStore()
        val initialKeyManager = DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = normalBlobStore)
        val originalPassphrase = initialKeyManager.getPassphrase()

        // Create an encrypted SQLCipher database with known data using originalPassphrase
        JdbcSQLiteDriver(passphrase = null).open(dbFile.absolutePath).use { connection ->
            connection.prepare(
                "CREATE TABLE note (id INTEGER PRIMARY KEY NOT NULL, title TEXT NOT NULL)",
            ).use { assertFalse(it.step()) }
            connection.prepare("INSERT INTO note (id, title) VALUES (?, ?)").use { stmt ->
                stmt.bindLong(1, 100L)
                stmt.bindText(2, "valuable-user-data")
                assertFalse(stmt.step())
            }
        }
        DesktopPlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(dbFile, originalPassphrase)
        assertTrue("Precondition: database exists and is encrypted", dbFile.exists())

        // Verify it can be read with originalPassphrase
        JdbcSQLiteDriver(originalPassphrase).open(dbFile.absolutePath).use { connection ->
            connection.prepare("SELECT title FROM note WHERE id = 100").use { stmt ->
                assertTrue(stmt.step())
                assertEquals("valuable-user-data", stmt.getText(0))
            }
        }

        val keyFile = File(keyDir, DesktopDatabaseKeyManager.KEY_FILE_NAME)
        assertTrue("Precondition: key file exists", keyFile.exists())

        // =========================================================================
        // LAUNCH 1: Transient DPAPI unwrap failure
        // =========================================================================
        val transientFailingBlobStore = object : SecureBlobStore by normalBlobStore {
            override fun unprotect(sealed: ByteArray): ByteArray {
                throw RuntimeException("Transient Windows DPAPI RPC failure (e.g. RPC_S_SERVER_UNAVAILABLE)")
            }
        }

        val launch1KeyManager = DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = transientFailingBlobStore)
        try {
            // Real startup executes: val passphrase = keyManager.getPassphrase()
            launch1KeyManager.getPassphrase()
            fail("Launch 1 must fail when unprotect throws")
        } catch (e: Exception) {
            println("[Test] Launch 1 failed as expected: ${e.message}")
        }

        // Assert state immediately after Launch 1:
        assertTrue("Launch 1: original DB still exists", dbFile.exists())
        val unrecoverableFilesAfterLaunch1 = keyDir.listFiles { _, name ->
            name.startsWith("${DesktopDatabaseKeyManager.KEY_FILE_NAME}.unrecoverable-")
        }.orEmpty()

        println("=== FILESYSTEM STATE AFTER LAUNCH 1 ===")
        println("notes.db exists: ${dbFile.exists()}")
        println("notes-db.key exists: ${keyFile.exists()}")
        println("unrecoverable key files: ${unrecoverableFilesAfterLaunch1.map { it.name }}")

        // Invariant verified: original key file is preserved intact in place
        assertEquals(0, unrecoverableFilesAfterLaunch1.size)
        assertTrue("Launch 1: original key file remains intact in place", keyFile.exists())

        // =========================================================================
        // LAUNCH 2: Subsequent startup with normal DPAPI behavior restored
        // =========================================================================
        val launch2KeyManager = DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = normalBlobStore)

        // Real application startup wiring from PlatformModule.kt lines 89-95:
        val launch2Passphrase = launch2KeyManager.getPassphrase()
        DesktopPlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(dbFile, launch2Passphrase)

        val quarantinedDbFilesAfterLaunch2 = rootDir.listFiles { _, name ->
            name.startsWith("notes.db.quarantined-")
        }.orEmpty()

        println("=== FILESYSTEM STATE AFTER LAUNCH 2 ===")
        println("notes.db exists: ${dbFile.exists()}")
        println("notes-db.key exists: ${keyFile.exists()}")
        println("quarantined DB files: ${quarantinedDbFilesAfterLaunch2.map { it.name }}")
        println("Passphrase equals original: ${launch2Passphrase.contentEquals(originalPassphrase)}")

        // Safe invariants verified:
        assertTrue("Launch 2 reloads the exact same passphrase as original", launch2Passphrase.contentEquals(originalPassphrase))
        assertTrue("No database files are quarantined", quarantinedDbFilesAfterLaunch2.isEmpty())
        assertTrue("Original notes.db remains intact in main path", dbFile.exists())

        // Verify data in notes.db is still intact and queryable
        JdbcSQLiteDriver(launch2Passphrase).open(dbFile.absolutePath).use { connection ->
            connection.prepare("SELECT title FROM note WHERE id = 100").use { stmt ->
                assertTrue(stmt.step())
                assertEquals("valuable-user-data", stmt.getText(0))
            }
        }
    }
}
