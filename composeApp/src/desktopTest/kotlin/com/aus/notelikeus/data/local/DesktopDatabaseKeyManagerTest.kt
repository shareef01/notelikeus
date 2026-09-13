package com.aus.notelikeus.data.local

import com.aus.notelikeus.data.attachments.IdentitySecureBlobStore
import com.aus.notelikeus.data.attachments.SecureBlobStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Persistence tests for the Desktop notes-DB passphrase.
 *
 * Real DPAPI is not exercised here ÔÇö desktop unit tests run on Linux CI without Crypt32.
 */
class DesktopDatabaseKeyManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun mintsPersistsAndReloadsSamePassphrase() {
        val keyDir = temp.newFolder("notelikeus")
        val first = DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = IdentitySecureBlobStore())
        val passphrase = first.getPassphrase()
        assertEquals(32, passphrase.size)
        assertTrue(File(keyDir, DesktopDatabaseKeyManager.KEY_FILE_NAME).exists())

        val second = DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = IdentitySecureBlobStore())
        assertArrayEquals(passphrase, second.getPassphrase())
    }

    @Test
    fun returnsDefensiveCopyFromCache() {
        val keyDir = temp.newFolder("notelikeus")
        val manager = DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = IdentitySecureBlobStore())
        val a = manager.getPassphrase()
        a[0] = (a[0] + 1).toByte()
        assertFalse(a.contentEquals(manager.getPassphrase()))
    }

    @Test
    fun unwrapFailurePreservesKeyFileAndDoesNotMintReplacement() {
        val keyDir = temp.newFolder("notelikeus")
        val keyFile = File(keyDir, DesktopDatabaseKeyManager.KEY_FILE_NAME)
        keyFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        val failing = object : SecureBlobStore {
            override fun protect(plaintext: ByteArray): ByteArray = plaintext.copyOf()
            override fun unprotect(sealed: ByteArray): ByteArray {
                throw IllegalStateException("unwrap refused")
            }
        }

        try {
            DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = failing).getPassphrase()
            fail("expected unwrap failure")
        } catch (_: IllegalStateException) {
            // expected
        }

        val preserved = keyDir.listFiles()?.filter {
            it.name.startsWith("${DesktopDatabaseKeyManager.KEY_FILE_NAME}.unrecoverable-")
        }.orEmpty()
        assertEquals(0, preserved.size)
        assertTrue("Key file must remain intact", keyFile.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), keyFile.readBytes())
    }

    @Test
    fun structurallyCorruptedKeyLengthThrowsAndPreservesKey() {
        val keyDir = temp.newFolder("notelikeus")
        val keyFile = File(keyDir, DesktopDatabaseKeyManager.KEY_FILE_NAME)
        // Corrupted payload: only 16 bytes instead of 32
        val shortBytes = ByteArray(16) { 0x42.toByte() }
        keyFile.writeBytes(shortBytes)

        try {
            DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = IdentitySecureBlobStore()).getPassphrase()
            fail("expected IllegalArgumentException for invalid key size")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        assertTrue("Key file must remain intact", keyFile.exists())
        assertArrayEquals(shortBytes, keyFile.readBytes())
    }

    @Test
    fun validKeyWithTrulyCorruptedDatabaseStillQuarantines() {
        val keyDir = temp.newFolder("notelikeus")
        val manager = DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = IdentitySecureBlobStore())
        val passphrase = manager.getPassphrase()

        val dbDir = temp.newFolder("db")
        val dbFile = File(dbDir, "notes.db")
        // Write completely corrupt junk into DB file
        dbFile.writeBytes(byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66))

        DesktopPlaintextDatabaseMigrator.migrateToEncryptedIfNeeded(dbFile, passphrase)

        val quarantined = dbDir.listFiles()?.filter { it.name.startsWith("notes.db.quarantined-") }.orEmpty()
        assertEquals("Corrupted DB file must be quarantined", 1, quarantined.size)
        assertFalse("Original corrupted DB file should no longer exist at original path", dbFile.exists())
    }
}
