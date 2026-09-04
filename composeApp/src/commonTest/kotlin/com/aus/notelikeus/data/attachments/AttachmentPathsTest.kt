package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentPathsTest {
    @Test
    fun pendingAndR2PrefixesRoundTrip() {
        val id = createAttachmentId()
        assertTrue(isPendingAttachment(pendingStoragePath(id)))
        val r2Path = "${ATTACHMENT_R2_PREFIX}owners/u/notes/1/$id"
        assertTrue(isR2Attachment(r2Path))
        assertEquals("owners/u/notes/1/$id", r2ObjectKey(r2Path))
    }

    @Test
    fun mergeKeepsPendingUntilRemoteRegistered() {
        val note = Note(id = 9L, title = "t", content = "c", timestamp = 1L, color = 0, position = 0)
        val pending = Attachment(
            id = "att-pending",
            noteId = 9L,
            storagePath = pendingStoragePath("att-pending"),
            type = "image",
        )
        val withPending = note.copy(attachments = listOf(pending))
        val kept = mergeAttachmentsIntoNotes(listOf(withPending), emptyList())
        assertEquals(1, kept.single().attachments.size)
        assertTrue(isPendingAttachment(kept.single().attachments.single().storagePath))

        val replaced = mergeAttachmentsIntoNotes(
            listOf(withPending),
            listOf(
                NoteAttachmentMetadata(
                    attachmentId = "att-pending",
                    noteId = "9",
                    objectKey = "owners/u/notes/9/att-pending",
                    mimeType = "image/png",
                    sizeBytes = 10,
                    attachmentType = "image",
                ),
            ),
        )
        assertEquals(1, replaced.single().attachments.size)
        assertTrue(isR2Attachment(replaced.single().attachments.single().storagePath))
    }

    @Test
    fun firstImageAttachmentSkipsNonImages() {
        val pdf = Attachment("doc", 1L, pendingStoragePath("doc"), "file", "application/pdf", 10)
        val png = Attachment("pic", 1L, pendingStoragePath("pic"), "image", "image/png", 10)
        assertEquals(png, firstImageAttachment(listOf(pdf, png)))
        assertEquals(null, firstImageAttachment(listOf(pdf)))
    }

    @Test
    fun attachmentsKeyDetectsMetadataChanges() {
        val a = Attachment("1", 1L, pendingStoragePath("1"), "image", "image/png", 10)
        val b = a.copy(sizeBytes = 11)
        assertFalse(attachmentsKey(listOf(a)) == attachmentsKey(listOf(b)))
    }
}
