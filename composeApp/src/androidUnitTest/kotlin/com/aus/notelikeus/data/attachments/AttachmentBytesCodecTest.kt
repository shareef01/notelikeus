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

class AttachmentBytesCodecTest {

    private fun softwareKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTripsPlaintextWithAad() {
        val key = softwareKey()
        val plaintext = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
        val aad = "photo.png".toByteArray()
        val sealed = AttachmentBytesCodec.seal(key, plaintext, aad)
        assertTrue(AttachmentBytesCodec.looksSealed(sealed))
        assertFalse(sealed.contentEquals(plaintext))
        assertArrayEquals(plaintext, AttachmentBytesCodec.open(key, sealed, aad))
    }

    @Test
    fun rejectsWrongAad() {
        val key = softwareKey()
        val sealed = AttachmentBytesCodec.seal(key, byteArrayOf(1, 2, 3), "a".toByteArray())
        try {
            AttachmentBytesCodec.open(key, sealed, "b".toByteArray())
            throw AssertionError("expected authentication failure")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val key = softwareKey()
        val sealed = AttachmentBytesCodec.seal(key, byteArrayOf(1, 2, 3), "a".toByteArray()).clone()
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        try {
            AttachmentBytesCodec.open(key, sealed, "a".toByteArray())
            throw AssertionError("expected authentication failure")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun freshIvEachSeal() {
        val key = softwareKey()
        val aad = "x".toByteArray()
        val first = AttachmentBytesCodec.seal(key, byteArrayOf(9), aad)
        val second = AttachmentBytesCodec.seal(key, byteArrayOf(9), aad)
        assertFalse(first.contentEquals(second))
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

class AttachmentAtRestMigratorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun migratesPlaintextAttachmentAndLeavesSealedAlone() {
        val protector = SoftwareAttachmentBytesProtector()
        val root = temp.newFolder("attachments")
        val plain = File(root, "a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val already = File(root, "b.jpg").apply {
            writeBytes(protector.seal(byteArrayOf(9, 9), name.toByteArray()))
        }
        val alreadyBytes = already.readBytes()

        AttachmentAtRestMigrator.migrateAttachmentsRoot(root, protector)

        assertTrue(protector.looksSealed(plain.readBytes()))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), protector.open(plain.readBytes(), plain.name.toByteArray()))
        assertArrayEquals(alreadyBytes, already.readBytes())
    }

    @Test
    fun migratesStagingBinWithOwnerAad() {
        val protector = SoftwareAttachmentBytesProtector()
        val root = temp.newFolder("pending-attachments")
        val owner = File(root, "owner-1").also { it.mkdirs() }
        val bin = File(owner, "att-1.bin").apply { writeBytes(byteArrayOf(7, 7, 7)) }

        AttachmentAtRestMigrator.migratePendingStagingRoot(root, protector)

        assertTrue(protector.looksSealed(bin.readBytes()))
        assertArrayEquals(
            byteArrayOf(7, 7, 7),
            protector.open(bin.readBytes(), "owner-1/att-1".toByteArray()),
        )
    }

    @Test
    fun dualReadViaLocalStorageStyleOpen() {
        val protector = SoftwareAttachmentBytesProtector()
        val plaintext = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        assertArrayEquals(plaintext, protector.open(plaintext, "legacy.png".toByteArray()))
        val sealed = protector.seal(plaintext, "legacy.png".toByteArray())
        assertArrayEquals(plaintext, protector.open(sealed, "legacy.png".toByteArray()))
        assertNull(protector.open(sealed, "other.png".toByteArray()))
    }

    @Test
    fun noopProtectorDoesNotMigrate() {
        val root = temp.newFolder("attachments-noop")
        val plain = File(root, "a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        AttachmentAtRestMigrator.migrateAttachmentsRoot(root, NoopAttachmentBytesProtector)
        assertArrayEquals(byteArrayOf(1, 2, 3), plain.readBytes())
        assertNotNull(plain)
    }
}
