package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentBlobUploadResult
import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.remote.SupabaseTransportException
import com.aus.notelikeus.data.sync.FakeNoteDao
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.LocalCommitResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The attachments Worker refuses (403) every upload for a note the server has never seen, and the
 * upload pass runs *before* the note is pushed. If that refusal ended the batch, a new note with a
 * picture could never be pushed and would stay pending forever.
 *
 * So a 403 keeps the attachment exactly as it was — still `pending:`, bytes still staged — and lets
 * the note go up without it; staged reconciliation uploads it once the server has the note. Anything
 * that is not a refusal (a 5xx, a timeout) still fails the batch.
 */
class AttachmentRefusedUploadTest {

    @Test
    fun `a refused upload keeps the attachment pending instead of failing the batch`() =
        runTest(timeout = TIMEOUT) {
            val staging = ReadableStaging().apply { put(REFUSED_ID) }
            val service = serviceWith(staging, FailingTransport(status = 403))

            val synced = service.upload(noteWith(pendingAttachment(REFUSED_ID)))

            val kept = synced.attachments.single()
            assertEquals(REFUSED_ID, kept.id)
            assertEquals(
                pendingStoragePath(REFUSED_ID),
                kept.storagePath,
                "a refused upload must leave the reference local so it can be retried",
            )
            assertEquals(emptyList<String>(), staging.released, "the only copy of the bytes must stay")
        }

    @Test
    fun `a server error still fails the batch`() =
        runTest(timeout = TIMEOUT) {
            val staging = ReadableStaging().apply { put(REFUSED_ID) }
            val service = serviceWith(staging, FailingTransport(status = 500))

            val failure = assertFailsWith<SupabaseTransportException> {
                service.upload(noteWith(pendingAttachment(REFUSED_ID)))
            }

            assertEquals(500, failure.statusCode)
        }

    @Test
    fun `a refusal on one attachment does not stop the next one uploading`() =
        runTest(timeout = TIMEOUT) {
            val staging = ReadableStaging().apply {
                put(REFUSED_ID)
                put(UPLOADED_ID)
            }
            val service = serviceWith(staging, FailingTransport(status = 403, refusedId = REFUSED_ID))

            val synced = service.upload(
                noteWith(pendingAttachment(REFUSED_ID), pendingAttachment(UPLOADED_ID)),
            )

            val byId = synced.attachments.associateBy { it.id }
            assertEquals(pendingStoragePath(REFUSED_ID), byId.getValue(REFUSED_ID).storagePath)
            assertTrue(
                isR2Attachment(byId.getValue(UPLOADED_ID).storagePath),
                "the attachment that was not refused should have been uploaded",
            )
        }

    // ---- fixtures ----

    private fun serviceWith(staging: AttachmentStagingStore, transport: AttachmentBlobTransport) =
        AttachmentSyncService(
            blobTransport = transport,
            metadata = null,
            localStorage = NoLocalStorage(),
            noteDao = FakeNoteDao(),
            staging = staging,
            ownerIdProvider = { OWNER },
            attachmentsEnabled = { true },
        )

    private fun pendingAttachment(id: String) = Attachment(
        id = id,
        noteId = NOTE_ID,
        storagePath = pendingStoragePath(id),
        type = "image",
    )

    private fun noteWith(vararg attachments: Attachment) = Note(
        id = NOTE_ID,
        title = "n",
        content = "",
        timestamp = 1L,
        color = 0,
        attachments = attachments.toList(),
    )

    private suspend fun AttachmentSyncService.upload(note: Note): Note =
        when (val uploaded = syncNoteAttachments(note, LocalCommitGate.capture(null))) {
            is LocalCommitResult.Applied -> uploaded.value
            LocalCommitResult.StaleGeneration ->
                error("the token was taken for this call, so its generation cannot already be stale")
        }

    /** Fails every upload with [status], or only the one for [refusedId] when it is given. */
    private class FailingTransport(
        private val status: Int,
        private val refusedId: String? = null,
    ) : AttachmentBlobTransport {
        override suspend fun captureContext(): AttachmentRemoteContext =
            AttachmentRemoteContext(ownerId = OWNER, accessToken = "token-$OWNER")

        override suspend fun upload(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
            bytes: ByteArray,
            mimeType: String,
        ): AttachmentBlobUploadResult {
            if (refusedId == null || attachmentId == refusedId) {
                throw SupabaseTransportException("attachments", status, "")
            }
            return AttachmentBlobUploadResult(
                objectKey = "owners/${context.ownerId}/notes/$noteId/$attachmentId",
                sizeBytes = bytes.size.toLong(),
                mimeType = mimeType,
            )
        }

        override suspend fun download(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
        ): ByteArray = ByteArray(0)

        override suspend fun delete(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
        ) {}
    }

    private class ReadableStaging : AttachmentStagingStore {
        private val entries = mutableMapOf<String, Pair<StagedAttachment, ByteArray>>()
        val released = mutableListOf<String>()

        fun put(id: String) {
            val bytes = ByteArray(8) { 3 }
            entries[id] = StagedAttachment(
                attachmentId = id,
                ownerId = OWNER,
                noteId = NOTE_ID,
                mimeType = "image/jpeg",
                sizeBytes = bytes.size.toLong(),
                createdAt = 0L,
            ) to bytes
        }

        override suspend fun stage(
            attachmentId: String,
            ownerId: String,
            noteId: Long?,
            bytes: ByteArray,
            mimeType: String,
        ): StagedAttachment? = null

        override suspend fun readBytes(attachmentId: String, ownerId: String): ByteArray? =
            entries[attachmentId]?.second

        override suspend fun metadata(attachmentId: String, ownerId: String): StagedAttachment? =
            entries[attachmentId]?.first

        override suspend fun isStaged(attachmentId: String, ownerId: String): Boolean? =
            attachmentId in entries

        override suspend fun bindNote(attachmentId: String, ownerId: String, noteId: Long) {}

        override suspend fun release(attachmentId: String, ownerId: String) {
            released += attachmentId
            entries.remove(attachmentId)
        }

        override suspend fun list(ownerId: String): List<StagedAttachment> = entries.values.map { it.first }
    }

    private class NoLocalStorage : AttachmentLocalStorage {
        override fun persistImageBytes(bytes: ByteArray, extension: String): String? = null

        override fun readBytes(storagePath: String): ByteArray? = null

        override fun exists(storagePath: String): Boolean? = null

        override fun deleteIfLocal(storagePath: String) {}
    }

    private companion object {
        const val OWNER = "owner-a"
        const val NOTE_ID = 1L
        const val REFUSED_ID = "refused-1"
        const val UPLOADED_ID = "up-1"
        val TIMEOUT = 60.seconds
    }
}
