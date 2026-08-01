package com.aus.notelikeus.data.local

import com.aus.notelikeus.data.local.DatabaseKeyManager.Companion.hexToByteArray
import com.aus.notelikeus.data.local.DatabaseKeyManager.Companion.toHexString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class DatabaseKeyManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `hex round trip`() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x10, 0xff.toByte())
        assertEquals("000f10ff", bytes.toHexString())
        assertArrayEquals(bytes, "000f10ff".hexToByteArray())
    }

    @Test
    fun `PassphraseFileCodec round trips with software AES key`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val passphrase = ByteArray(32) { it.toByte() }
        val payload = PassphraseFileCodec.encrypt(key, passphrase.toHexString())
        val restoredHex = PassphraseFileCodec.decrypt(key, payload)
        assertArrayEquals(passphrase, restoredHex.hexToByteArray())
    }

    @Test
    fun `PassphraseFileCodec rejects tampered ciphertext`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val payload = PassphraseFileCodec.encrypt(key, "abcd").clone()
        payload[payload.lastIndex] = (payload.last().toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) {
            PassphraseFileCodec.decrypt(key, payload)
        }
    }

    @Test
    fun `PassphraseFileCodec rejects wrong magic`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val payload = PassphraseFileCodec.encrypt(key, "abcd")
        payload[0] = 'X'.code.toByte()
        assertThrows(Exception::class.java) {
            PassphraseFileCodec.decrypt(key, payload)
        }
    }

    @Test
    fun `swapEncryptedIntoPlace replaces the source and removes the backup`() {
        val dir = temporaryFolder.newFolder("swap-ok")
        val databaseFile = File(dir, "notes.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val encryptedTemp = File(dir, "notes.db-encrypted-temp").apply { writeBytes(byteArrayOf(4, 5, 6, 7)) }

        assertTrue(PlaintextDatabaseMigrator.swapEncryptedIntoPlace(databaseFile, encryptedTemp))
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), databaseFile.readBytes())
        assertFalse(encryptedTemp.exists())
        assertFalse(File(dir, "notes.db.pre-encrypt").exists())
    }

    @Test
    fun `swapEncryptedIntoPlace restores the source when the encrypted copy is missing`() {
        val dir = temporaryFolder.newFolder("swap-restore")
        val databaseFile = File(dir, "notes.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val encryptedTemp = File(dir, "notes.db-encrypted-temp") // never written

        assertFalse(PlaintextDatabaseMigrator.swapEncryptedIntoPlace(databaseFile, encryptedTemp))
        assertArrayEquals(byteArrayOf(1, 2, 3), databaseFile.readBytes())
        assertFalse(File(dir, "notes.db.pre-encrypt").exists())
    }

    @Test
    fun `swapEncryptedIntoPlace leaves the source intact when it cannot be moved aside`() {
        val dir = temporaryFolder.newFolder("swap-blocked")
        val databaseFile = File(dir, "notes.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val encryptedTemp = File(dir, "notes.db-encrypted-temp").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        // A non-empty directory at the backup path can neither be deleted nor renamed over, so the
        // source move fails and the source must be left completely untouched.
        val backup = File(dir, "notes.db.pre-encrypt").apply {
            mkdirs()
            File(this, "lock").writeBytes(byteArrayOf(1))
        }

        assertFalse(PlaintextDatabaseMigrator.swapEncryptedIntoPlace(databaseFile, encryptedTemp))
        assertArrayEquals(byteArrayOf(1, 2, 3), databaseFile.readBytes())
        assertTrue(encryptedTemp.exists())
        assertTrue(backup.exists())
    }
}
