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
 * Real DPAPI is not exercised here — desktop unit tests run on Linux CI without Crypt32.
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
        assertEquals(1, preserved.size)
        assertFalse(keyFile.exists())
    }
}
