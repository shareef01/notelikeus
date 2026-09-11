package com.aus.notelikeus.data.attachments

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Software-key tests for the shared NLA1 codec and Desktop sealing path.
 *
 * Real DPAPI is not exercised here — desktop unit tests run on Linux CI without Crypt32.
 */
class DesktopAttachmentCryptoTest {

    private fun softwareKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun codecRoundTripsWithAad() {
        val key = softwareKey()
        val plaintext = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
        val aad = "photo.png".toByteArray()
        val sealed = AttachmentBytesCodec.seal(key, plaintext, aad)
        assertTrue(AttachmentBytesCodec.looksSealed(sealed))
        assertArrayEquals(plaintext, AttachmentBytesCodec.open(key, sealed, aad))
    }

    @Test
    fun jpegDoesNotLookSealed() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())
        assertFalse(AttachmentBytesCodec.looksSealed(jpeg))
    }
}

class SoftwareAttachmentBytesProtector(
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
) : AttachmentBytesProtector {
    override fun looksSealed(payload: ByteArray): Boolean = AttachmentBytesCodec.looksSealed(payload)

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray =
        AttachmentBytesCodec.seal(key, plaintext, aad)

    override fun open(payload: ByteArray, aad: ByteArray): ByteArray? {
        if (!looksSealed(payload)) return payload
        return runCatching { AttachmentBytesCodec.open(key, payload, aad) }.getOrNull()
    }
}

/** Identity blob store — stands in for DPAPI on Linux CI. */
class IdentitySecureBlobStore : SecureBlobStore {
    override fun protect(plaintext: ByteArray): ByteArray = plaintext.copyOf()
    override fun unprotect(sealed: ByteArray): ByteArray = sealed.copyOf()
}

class DesktopAttachmentBytesProtectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun persistsKeyAndRoundTripsSealedPayload() {
        val keyDir = temp.newFolder("notelikeus")
        val protector = DesktopAttachmentBytesProtector(
            keyDir = keyDir,
            blobStore = IdentitySecureBlobStore(),
        )
        val plaintext = byteArrayOf(1, 2, 3, 4, 5)
        val aad = "a.jpg".toByteArray()
        val sealed = protector.seal(plaintext, aad)
        assertTrue(protector.looksSealed(sealed))
        assertArrayEquals(plaintext, protector.open(sealed, aad))
        assertTrue(File(keyDir, DesktopAttachmentBytesProtector.KEY_FILE_NAME).exists())

        val reloaded = DesktopAttachmentBytesProtector(
            keyDir = keyDir,
            blobStore = IdentitySecureBlobStore(),
        )
        assertArrayEquals(plaintext, reloaded.open(sealed, aad))
    }

    @Test
    fun dualReadReturnsLegacyPlaintext() {
        val protector = DesktopAttachmentBytesProtector(
            keyDir = temp.newFolder("keys"),
            blobStore = IdentitySecureBlobStore(),
        )
        val legacy = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        assertArrayEquals(legacy, protector.open(legacy, "x.jpg".toByteArray()))
    }
}

class DesktopAttachmentLocalStorageSealTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun writesSealedBytesAndReadsPlaintext() {
        val home = temp.newFolder("home")
        val protector = SoftwareAttachmentBytesProtector()
        val storage = DesktopAttachmentLocalStorage(protector = protector, homeDir = home)
        val path = storage.persistImageBytes(byteArrayOf(9, 8, 7), "png")
        assertNotNull(path)
        val onDisk = File(localFilePath(path!!)!!)
        assertTrue(protector.looksSealed(onDisk.readBytes()))
        assertArrayEquals(byteArrayOf(9, 8, 7), storage.readBytes(path))
    }

    @Test
    fun migratesPlaintextOnColdStartPattern() {
        val home = temp.newFolder("home")
        val attachments = File(home, ".notelikeus/attachments").also { it.mkdirs() }
        val plain = File(attachments, "legacy.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val protector = SoftwareAttachmentBytesProtector()

        AttachmentAtRestMigrator.migrateAttachmentsRoot(attachments, protector)

        assertTrue(protector.looksSealed(plain.readBytes()))
        assertArrayEquals(
            byteArrayOf(4, 5, 6),
            protector.open(plain.readBytes(), plain.name.toByteArray()),
        )
        assertNull(protector.open(plain.readBytes(), "other.jpg".toByteArray()))
    }
}
