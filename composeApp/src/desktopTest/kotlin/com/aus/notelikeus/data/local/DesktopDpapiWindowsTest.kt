package com.aus.notelikeus.data.local

import com.aus.notelikeus.data.attachments.DesktopAttachmentBytesProtector
import com.aus.notelikeus.data.attachments.DpapiSecureBlobStore
import com.aus.notelikeus.platform.Dpapi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises real Crypt32 DPAPI on Windows. Skipped on Linux/mac CI (no Crypt32).
 *
 * The identity-store suites in [DesktopDatabaseKeyManagerTest] / attachment crypto tests stay the
 * cross-platform coverage; this class is what proves the shipped Windows path.
 */
class DesktopDpapiWindowsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun requireWindows() {
        assumeTrue("Real DPAPI requires Windows", DesktopSqliteFlags.isWindows())
    }

    @Test
    fun jdbcSqliteIsDefaultOnWindows() {
        val previous = System.getProperty(DesktopSqliteFlags.PROPERTY)
        try {
            System.clearProperty(DesktopSqliteFlags.PROPERTY)
            assertTrue(DesktopSqliteFlags.useJdbcSqlite())
        } finally {
            if (previous == null) {
                System.clearProperty(DesktopSqliteFlags.PROPERTY)
            } else {
                System.setProperty(DesktopSqliteFlags.PROPERTY, previous)
            }
        }
    }

    @Test
    fun databaseKeyManagerPersistsUnderRealDpapi() {
        val keyDir = temp.newFolder("db-keys")
        val first = DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = DpapiDatabaseKeyBlobStore)
        val passphrase = first.getPassphrase()
        assertEquals(32, passphrase.size)

        val onDisk = File(keyDir, DesktopDatabaseKeyManager.KEY_FILE_NAME).readBytes()
        assertFalse(onDisk.contentEquals(passphrase))

        val second = DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = DpapiDatabaseKeyBlobStore)
        assertArrayEquals(passphrase, second.getPassphrase())
    }

    @Test
    fun attachmentKeyPersistsUnderRealDpapi() {
        val keyDir = temp.newFolder("att-keys")
        val protector = DesktopAttachmentBytesProtector(
            keyDir = keyDir,
            blobStore = DpapiSecureBlobStore,
        )
        val plaintext = byteArrayOf(9, 8, 7, 6)
        val aad = "win.jpg".toByteArray()
        val sealed = protector.seal(plaintext, aad)
        assertTrue(protector.looksSealed(sealed))

        val keyFile = File(keyDir, DesktopAttachmentBytesProtector.KEY_FILE_NAME)
        assertTrue(keyFile.exists())
        // DPAPI ciphertext is larger than the raw 32-byte AES key an identity store would write.
        assertTrue(keyFile.length() > 32L)

        val reloaded = DesktopAttachmentBytesProtector(
            keyDir = keyDir,
            blobStore = DpapiSecureBlobStore,
        )
        assertArrayEquals(plaintext, reloaded.open(sealed, aad))
    }

    @Test
    fun wrongEntropyCannotUnwrapDatabaseKeyBlob() {
        val keyDir = temp.newFolder("wrong-entropy")
        val manager = DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = DpapiDatabaseKeyBlobStore)
        manager.getPassphrase()
        val sealed = File(keyDir, DesktopDatabaseKeyManager.KEY_FILE_NAME).readBytes()

        var failed = false
        try {
            Dpapi.unprotect(sealed, Dpapi.attachmentKeyEntropy)
        } catch (_: RuntimeException) {
            failed = true
        }
        assertTrue(failed)
    }
}
