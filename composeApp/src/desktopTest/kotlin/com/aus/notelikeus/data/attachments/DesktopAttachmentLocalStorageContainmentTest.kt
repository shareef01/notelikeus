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

/**
 * Filesystem-boundary tests for DesktopAttachmentLocalStorage path containment.
 *
 * Uses a temporary home directory so the real `~/.notelikeus/attachments` is never touched.
 */
class DesktopAttachmentLocalStorageContainmentTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun storageWithHome(home: File): DesktopAttachmentLocalStorage {
        return DesktopAttachmentLocalStorage(homeDir = home)
    }

    @Test
    fun readsAndDeletesOnlyChildrenOfTheAttachmentsRoot() {
        val home = temp.newFolder("home")
        val subject = storageWithHome(home)
        val stored = subject.persistImageBytes(byteArrayOf(1, 2, 3, 4), "png")
        assertNotNull(stored)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), subject.readBytes(stored!!))
        assertTrue(subject.exists(stored) == true)

        subject.deleteIfLocal(stored)
        assertFalse(File(localFilePath(stored)!!).exists())
    }

    @Test
    fun refusesTraversalAndExternalAbsolutePaths() {
        val home = temp.newFolder("home")
        val subject = storageWithHome(home)
        val attachments = File(home, ".notelikeus/attachments").also { it.mkdirs() }
        val secret = temp.newFile("secret.txt").also { it.writeText("top-secret") }
        val evilSibling = File(home, ".notelikeus/attachments-evil").also { it.mkdirs() }
        val evilFile = File(evilSibling, "photo.png").also { it.writeBytes(byteArrayOf(9, 9, 9)) }

        // Canonical escape via ..
        val traversal = fileStoragePath(File(attachments, "../attachments-evil/photo.png").canonicalPath)
        assertNull(subject.readBytes(traversal))
        assertFalse(subject.exists(traversal) == true)
        subject.deleteIfLocal(traversal)
        assertTrue(evilFile.exists())

        // Absolute path outside the root
        val external = fileStoragePath(secret.absolutePath)
        assertNull(subject.readBytes(external))
        subject.deleteIfLocal(external)
        assertTrue(secret.exists())

        // Similarly-prefixed sibling directory
        val sibling = fileStoragePath(evilFile.absolutePath)
        assertNull(subject.readBytes(sibling))
        subject.deleteIfLocal(sibling)
        assertTrue(evilFile.exists())
    }
}
