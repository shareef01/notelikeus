package com.aus.notelikeus.data.attachments

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises interrupted migration with the real Android Keystore, in test-only cache files. */
@RunWith(AndroidJUnit4::class)
class AttachmentRecoveryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "audit-attachment-recovery").apply { mkdirs() }
    private val plaintext = byteArrayOf(1, 3, 5, 7, 9)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun keystoreCiphertextSurvivesProtectorRecreationAndRejectsWrongIdentity() {
        val aad = "device-image.jpg".toByteArray()
        val sealed = AndroidAttachmentBytesProtector().seal(plaintext, aad)
        val recreated = AndroidAttachmentBytesProtector()
        assertTrue(recreated.looksSealed(sealed))
        assertArrayEquals(plaintext, recreated.open(sealed, aad))
        assertNull(recreated.open(sealed, "other-image.jpg".toByteArray()))
        val tampered = sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertNull(recreated.open(tampered, aad))
    }

    @Test
    fun interruptedAttachmentSwapPublishesVerifiedCiphertext() {
        val attachments = File(root, "attachments").apply { mkdirs() }
        val destination = File(attachments, "device-image.jpg")
        val protector = AndroidAttachmentBytesProtector()
        val backup = File(attachments, "${destination.name}.pre-encrypt").apply { writeBytes(plaintext) }
        val pending = File(attachments, "${destination.name}.encrypt-tmp").apply {
            writeBytes(protector.seal(plaintext, destination.name.toByteArray()))
        }
        AttachmentAtRestMigrator.migrateAttachmentsRoot(attachments, protector)
        assertTrue(protector.looksSealed(destination.readBytes()))
        assertArrayEquals(plaintext, protector.open(destination.readBytes(), destination.name.toByteArray()))
        assertFalse(backup.exists())
        assertFalse(pending.exists())
    }

    @Test
    fun interruptedStagingSwapRejectsAnotherOwnersCiphertextAndResealsSource() {
        val staging = File(root, "pending")
        val owner = File(staging, "owner-a").apply { mkdirs() }
        val destination = File(owner, "image.bin")
        val protector = AndroidAttachmentBytesProtector()
        File(owner, "image.bin.pre-encrypt").writeBytes(plaintext)
        File(owner, "image.bin.encrypt-tmp").writeBytes(
            protector.seal(byteArrayOf(99), "owner-b/image".toByteArray()),
        )
        AttachmentAtRestMigrator.migratePendingStagingRoot(staging, protector)
        assertTrue(protector.looksSealed(destination.readBytes()))
        assertArrayEquals(plaintext, protector.open(destination.readBytes(), "owner-a/image".toByteArray()))
        assertNull(protector.open(destination.readBytes(), "owner-b/image".toByteArray()))
        assertFalse(File(owner, "image.bin.pre-encrypt").exists())
        assertFalse(File(owner, "image.bin.encrypt-tmp").exists())
    }
}
