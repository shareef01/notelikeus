package com.aus.notelikeus.data.local

import com.aus.notelikeus.data.attachments.IdentitySecureBlobStore
import com.aus.notelikeus.data.attachments.SecureBlobStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FU-2: a key file Windows can no longer unwrap must be reported at startup, not thrown from the
 * database singleton, and must stay exactly as it was.
 */
class UnreadableDatabaseKeyStartupTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A blob store that sealed fine on the old profile and cannot unwrap on this one. */
    private object UnwrapFails : SecureBlobStore {
        override fun protect(plaintext: ByteArray): ByteArray = plaintext.copyOf()
        override fun unprotect(sealed: ByteArray): ByteArray = error("CryptUnprotectData failed")
    }

    @Test
    fun `an unreadable key is reported, not thrown, and the key file is untouched`() {
        val keyDir = temp.newFolder(".notelikeus")
        DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = IdentitySecureBlobStore()).getPassphrase()
        val keyFile = File(keyDir, DesktopDatabaseKeyManager.KEY_FILE_NAME)
        val before = keyFile.readBytes()

        val failure = DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = UnwrapFails).checkReadable()

        assertNotNull("an unreadable key must be reported", failure)
        assertArrayEquals("the key file must not be changed", before, keyFile.readBytes())
        assertEquals(listOf(DesktopDatabaseKeyManager.KEY_FILE_NAME), keyDir.list()!!.toList())
    }

    @Test
    fun `a readable key reports nothing`() {
        val keyDir = temp.newFolder(".notelikeus")

        assertNull(DesktopDatabaseKeyManager(keyDir = keyDir, blobStore = IdentitySecureBlobStore()).checkReadable())
    }

    @Test
    fun `the notice says nothing was deleted and where the files are`() {
        val data = File(temp.root, "data")
        val keys = File(temp.root, "keys")

        val message = UnreadableDatabaseKeyNotice.message(data, keys)

        assertTrue(message.contains("Nothing has been deleted"))
        assertTrue(message.contains(data.absolutePath))
        assertTrue(message.contains(keys.absolutePath))
    }
}
