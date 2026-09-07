package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentBlobUploadResult
import com.aus.notelikeus.domain.model.Attachment
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What happens to attachment bytes and objects when an attachment is removed.
 *
 * The case that matters is a removal racing an upload, because that is how a deleted image comes
 * back: [mergeAttachmentsIntoNotes] rebuilds a note's attachments from the live metadata rows, so
 * a row left behind by an upload that finished after the removal reinstates the image on the next
 * hydrate.
 */
class AttachmentDeletionMatrixTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val deleted = mutableListOf<Pair<String, String>>()

    private open inner class RecordingTransport : AttachmentBlobTransport {
        override suspend fun upload(
            noteId: String,
            attachmentId: String,
            bytes: ByteArray,
            mimeType: String,
        ) = AttachmentBlobUploadResult(
            objectKey = "owners/owner-1/notes/$noteId/$attachmentId",
            sizeBytes = bytes.size.toLong(),
            mimeType = mimeType,
        )

        override suspend fun download(noteId: String, attachmentId: String): ByteArray = ByteArray(0)

        override suspend fun delete(noteId: String, attachmentId: String) {
            deleted += noteId to attachmentId
        }
    }

    private fun staging() = FileAttachmentStagingStore(
        root = temp.root.absolutePath.toPath(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun service(
        staging: AttachmentStagingStore,
        transport: AttachmentBlobTransport = RecordingTransport(),
    ) = AttachmentSyncService(
        blobTransport = transport,
        metadata = null,
        localStorage = mockk(relaxed = true),
        noteDao = mockk<NoteDao>(relaxed = true),
        staging = staging,
        ownerIdProvider = { "owner-1" },
        attachmentsEnabled = { true },
    )

    private fun pending(id: String) = Attachment(
        id = id,
        noteId = 1L,
        storagePath = pendingStoragePath(id),
        type = "image",
        mimeType = "image/png",
        sizeBytes = 3,
    )

    @Test
    fun `removing a never-uploaded attachment drops its staged bytes`() = runTest {
        val staging = staging()
        staging.stage("att-1", "owner-1", 1L, byteArrayOf(1, 2, 3), "image/png")

        service(staging).deleteAttachmentsForNote(1L, listOf(pending("att-1")))

        assertNull(staging.readBytes("att-1", "owner-1"))
        assertTrue(staging.list("owner-1").isEmpty())
    }

    @Test
    fun `removing a pending attachment also clears any row an in-flight upload committed`() =
        runTest {
            val staging = staging()
            staging.stage("att-2", "owner-1", 1L, byteArrayOf(1, 2, 3), "image/png")

            service(staging).deleteAttachmentsForNote(1L, listOf(pending("att-2")))

            // The regression this guards: an upload that finished after the user removed the
            // attachment leaves a live metadata row, and merging rebuilds the note from live
            // rows — so without this the deleted image reappears on the next hydrate.
            assertEquals(listOf("1" to "att-2"), deleted)
        }

    @Test
    fun `a server that refuses the delete still leaves no staged bytes behind`() = runTest {
        val staging = staging()
        staging.stage("att-3", "owner-1", 1L, byteArrayOf(1, 2, 3), "image/png")
        val failing = object : RecordingTransport() {
            override suspend fun delete(noteId: String, attachmentId: String) {
                throw java.io.IOException("offline")
            }
        }

        // Best effort by design: an unreachable server must not strand the local copy of an
        // attachment the user has already taken off the note, and must not fail the removal.
        service(staging, failing).deleteAttachmentsForNote(1L, listOf(pending("att-3")))

        assertNull(staging.readBytes("att-3", "owner-1"))
    }

    @Test
    fun `one account's removal never touches another account's staged copy`() = runTest {
        val staging = staging()
        staging.stage("att-4", "owner-1", 1L, byteArrayOf(1, 2, 3), "image/png")
        staging.stage("att-4", "owner-2", 1L, byteArrayOf(9, 9, 9), "image/png")

        service(staging).deleteAttachmentsForNote(1L, listOf(pending("att-4")))

        assertNull(staging.readBytes("att-4", "owner-1"))
        assertEquals(3, staging.readBytes("att-4", "owner-2")?.size)
    }

    @Test
    fun `staged bytes no live note references are released on reconciliation`() = runTest {
        val staging = staging()
        // Stands in for a note discarded, or deleted, before its attachment ever uploaded: the
        // bytes are staged but nothing in the database claims them any more.
        staging.stage("orphan", "owner-1", 1L, byteArrayOf(1, 2, 3), "image/png")
        assertEquals(1, staging.list("owner-1").size)

        service(staging).reconcileStagedAttachments()

        assertTrue(staging.list("owner-1").isEmpty())
        assertNull(staging.readBytes("orphan", "owner-1"))
    }
}
