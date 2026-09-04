package com.aus.notelikeus.data.backup

import com.aus.notelikeus.data.attachments.PendingAttachmentStore
import com.aus.notelikeus.data.attachments.pendingStoragePath
import com.aus.notelikeus.domain.model.Attachment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BackupAttachmentsTest {

    @AfterTest
    fun tearDown() {
        PendingAttachmentStore.clear()
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertTrue(decodeAttachmentBytes(encodeAttachmentBytes(bytes))!!.contentEquals(bytes))
        assertNull(decodeAttachmentBytes("%%%not-base64%%%"))
    }

    @Test
    fun exportReadsBytesAndImportRestoresPending() = runTest {
        val source = Attachment(
            id = "att-1",
            noteId = 9L,
            storagePath = "file:/tmp/photo.png",
            type = "image",
            mimeType = "image/png",
            sizeBytes = 4,
        )
        val dtos = attachmentsToBackupDtos(listOf(source)) { byteArrayOf(9, 8, 7, 6) }
        assertEquals(1, dtos.size)
        assertEquals("att-1", dtos[0].id)
        assertEquals("image/png", dtos[0].mimeType)
        assertEquals("png", dtos[0].extension)

        val restored = attachmentsFromBackupDtos(dtos, noteId = 3L, backupVersion = 3, localStorage = null)
        assertEquals(1, restored.size)
        assertEquals("att-1", restored[0].id)
        assertEquals(pendingStoragePath("att-1"), restored[0].storagePath)
        assertTrue(PendingAttachmentStore.peek("att-1")!!.bytes.contentEquals(byteArrayOf(9, 8, 7, 6)))
    }

    @Test
    fun legacyVersionTwoIgnoresAttachmentBytes() {
        val dto = AttachmentBackupDto(
            id = "legacy",
            type = "image",
            mimeType = "image/png",
            dataBase64 = encodeAttachmentBytes(byteArrayOf(1)),
            extension = "png",
        )
        val restored = attachmentsFromBackupDtos(
            listOf(dto),
            noteId = 1L,
            backupVersion = 2,
            localStorage = null,
        )
        assertTrue(restored.isEmpty())
        assertNull(PendingAttachmentStore.peek("legacy"))
    }

    @Test
    fun skipsDisallowedMimeAndEmptyReader() = runTest {
        val pdf = Attachment(
            id = "doc",
            noteId = 1L,
            storagePath = "file:/tmp/a.pdf",
            type = "image",
            mimeType = "application/pdf",
        )
        assertTrue(attachmentsToBackupDtos(listOf(pdf)) { byteArrayOf(1) }.isEmpty())
        assertTrue(attachmentsToBackupDtos(listOf(pdf), reader = null).isEmpty())
    }
}
