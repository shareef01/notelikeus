package com.aus.notelikeus.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LegacyAttachmentCleanupTest {

    @Test
    fun `deleteOrphanAttachmentFiles removes files and directory`() {
        val root = java.nio.file.Files.createTempDirectory("legacy-cleanup").toFile()
        val attachmentsDir = File(root, "attachments")
        attachmentsDir.mkdirs()
        File(attachmentsDir, "photo.jpg").writeText("legacy")
        File(attachmentsDir, "other.png").writeText("legacy")

        deleteOrphanAttachmentFiles(root)

        assertFalse(File(root, "attachments").exists())
    }

    @Test
    fun `deleteOrphanAttachmentFiles is no-op when directory missing`() {
        val root = java.nio.file.Files.createTempDirectory("legacy-cleanup").toFile()

        deleteOrphanAttachmentFiles(root)

        assertTrue(root.exists())
    }
}
