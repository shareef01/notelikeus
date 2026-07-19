package com.aus.notelikeus.data.local

import com.aus.notelikeus.data.local.DatabaseKeyManager.Companion.hexToByteArray
import com.aus.notelikeus.data.local.DatabaseKeyManager.Companion.toHexString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.KeyGenerator

class DatabaseKeyManagerTest {

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
}
