package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentBlobUploadResult
import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.sync.FakeNoteDao
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.LocalCommitResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The invariant these tests defend: **local source bytes are released only once a recoverable copy
 * exists elsewhere** (the contract stated on [AttachmentStagingStore.release]).
 *
 * `syncNoteAttachments` and `confirmCommittedAttachments` are two halves of one decision and must
 * agree. The upload pass deliberately *keeps* a `pending:` attachment whose bytes it could not read
 * — see [StrandedAttachmentTest] and the comment on [AttachmentStagingStore.isStaged] — precisely so
 * a locked encrypted store or a transient disk failure cannot delete the user's picture. The commit
 * pass must therefore release only the attachments the server actually accepted, and never the ones
 * the upload pass chose to keep. Releasing a kept one deletes the only copy while the note goes on
 * referencing it, which is the worst of both outcomes.
 */
class AttachmentCommittedReleaseTest {

    @Test
    fun `committing never releases bytes the staging store could not read`() =
        runTest(timeout = TIMEOUT) {
            val staging = UnreadableStaging()
            val service = serviceWith(staging)
            val snapshot = noteWith(pendingAttachment(HELD_ID))

            val synced = service.uploadUnderLiveToken(snapshot)
            assertEquals(
                listOf(HELD_ID),
                synced.attachments.map { it.id },
                "precondition: the upload pass keeps an attachment whose store cannot answer",
            )

            service.confirmCommittedAttachments(snapshot, synced)

            assertEquals(
                emptyList<String>(),
                staging.released,
                "an upload that never happened released the only copy of the user's picture",
            )
        }

    @Test
    fun `committing a successful staged upload releases the staged bytes`() =
        runTest(timeout = TIMEOUT) {
            val staging = ReadableStaging()
            staging.put(UPLOADED_ID, bytes = ByteArray(8) { 3 }, mimeType = "image/jpeg")
            val service = serviceWith(staging)
            val snapshot = noteWith(pendingAttachment(UPLOADED_ID))

            val synced = service.uploadUnderLiveToken(snapshot)
            assertTrue(
                isR2Attachment(synced.attachments.single().storagePath),
                "precondition: the upload did not commit",
            )

            service.confirmCommittedAttachments(snapshot, synced)

            assertEquals(
                listOf(UPLOADED_ID),
                staging.released,
                "source bytes should go once the server holds them",
            )
        }

    @Test
    fun `committing a successful local-file upload releases the local source file`() =
        runTest(timeout = TIMEOUT) {
            val localStorage = TrackingLocalStorage(bytes = ByteArray(8) { 5 })
            val service = serviceWith(UnreadableStaging(), localStorage)
            val snapshot = noteWith(fileAttachment(FILE_ID))

            val synced = service.uploadUnderLiveToken(snapshot)
            assertTrue(
                isR2Attachment(synced.attachments.single().storagePath),
                "precondition: the upload did not commit",
            )

            service.confirmCommittedAttachments(snapshot, synced)

            assertEquals(listOf(LOCAL_FILE_PATH), localStorage.deleted)
        }

    @Test
    fun `committing leaves an attachment the store could not read for a local file intact`() =
        runTest(timeout = TIMEOUT) {
            val localStorage = TrackingLocalStorage(bytes = null)
            val service = serviceWith(UnreadableStaging(), localStorage)
            val snapshot = noteWith(fileAttachment(FILE_ID))

            // readBytes is null and exists() cannot say, so the upload pass keeps the reference.
            val synced = service.uploadUnderLiveToken(snapshot)
            assertEquals(
                listOf(FILE_ID),
                synced.attachments.map { it.id },
                "precondition: an unreadable local file must not be dropped",
            )

            service.confirmCommittedAttachments(snapshot, synced)

            assertEquals(
                emptyList<String>(),
                localStorage.deleted,
                "a file that was never uploaded is the only copy and must not be deleted",
            )
        }

    // ---- fixtures ----

    private fun serviceWith(
        staging: AttachmentStagingStore,
        localStorage: AttachmentLocalStorage = TrackingLocalStorage(bytes = null),
    ) = AttachmentSyncService(
        blobTransport = RecordingTransport(),
        metadata = null,
        localStorage = localStorage,
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

    private fun fileAttachment(id: String) = Attachment(
        id = id,
        noteId = NOTE_ID,
        storagePath = fileStoragePath(LOCAL_FILE_PATH),
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

    private class RecordingTransport : AttachmentBlobTransport {
        override suspend fun captureContext(): AttachmentRemoteContext =
            AttachmentRemoteContext(ownerId = OWNER, accessToken = "token-$OWNER")

        override suspend fun upload(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
            bytes: ByteArray,
            mimeType: String,
        ) = AttachmentBlobUploadResult(
            objectKey = "owners/${context.ownerId}/notes/$noteId/$attachmentId",
            sizeBytes = bytes.size.toLong(),
            mimeType = mimeType,
        )

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

    /** Has the bytes, but cannot read or answer for them. A locked encrypted store under duress. */
    private class UnreadableStaging : AttachmentStagingStore {
        val released = mutableListOf<String>()

        override suspend fun stage(
            attachmentId: String,
            ownerId: String,
            noteId: Long?,
            bytes: ByteArray,
            mimeType: String,
        ): StagedAttachment? = null

        override suspend fun readBytes(attachmentId: String, ownerId: String): ByteArray? = null

        override suspend fun metadata(attachmentId: String, ownerId: String): StagedAttachment? = null

        override suspend fun isStaged(attachmentId: String, ownerId: String): Boolean? = null

        override suspend fun bindNote(attachmentId: String, ownerId: String, noteId: Long) {}

        override suspend fun release(attachmentId: String, ownerId: String) {
            released += attachmentId
        }

        override suspend fun list(ownerId: String): List<StagedAttachment> = emptyList()
    }

    /** A healthy staging store: the upload pass can read these bytes and will commit them. */
    private class ReadableStaging : AttachmentStagingStore {
        private val entries = mutableMapOf<String, Pair<StagedAttachment, ByteArray>>()
        val released = mutableListOf<String>()

        fun put(id: String, bytes: ByteArray, mimeType: String) {
            entries[id] = StagedAttachment(
                attachmentId = id,
                ownerId = OWNER,
                noteId = NOTE_ID,
                mimeType = mimeType,
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

    private class TrackingLocalStorage(private val bytes: ByteArray?) : AttachmentLocalStorage {
        val deleted = mutableListOf<String>()

        override fun persistImageBytes(bytes: ByteArray, extension: String): String? = null

        override fun readBytes(storagePath: String): ByteArray? = bytes

        override fun exists(storagePath: String): Boolean? = null

        override fun deleteIfLocal(storagePath: String) {
            // The real storages resolve the `file:` prefix internally (see
            // DesktopAttachmentLocalStorage.resolveContainedFile), so record the file that would
            // actually be deleted rather than the raw storage path.
            localFilePath(storagePath)?.let { deleted += it }
        }
    }

    /**
     * Uploads one note under a **live** generation token that claims no account.
     *
     * These lanes are about the local fence, the immutable remote identity and the staged-bytes
     * bookkeeping rather than about which generation authorizes an upload —
     * `AttachmentUploadRemoteStartFenceTest` covers that, and `syncNoteAttachments` no longer has an
     * un-authorized path to drive: every upload takes a grant from the caller's token. The token here
     * is taken for this call, so its generation is current and the upload proceeds exactly as the lane
     * expects. Its null initiating uid is the guest/staging shape these fixtures model, so the
     * owner-coherence check (covered by AURSF-11/12) deliberately does not apply to them.
     */
    private suspend fun AttachmentSyncService.uploadUnderLiveToken(note: Note): Note =
        when (val uploaded = syncNoteAttachments(note, LocalCommitGate.capture(null))) {
            is LocalCommitResult.Applied -> uploaded.value
            LocalCommitResult.StaleGeneration ->
                error("the token was taken for this call, so its generation cannot already be stale")
        }
    private companion object {
        const val OWNER = "owner-a"
        const val NOTE_ID = 1L
        const val HELD_ID = "held-1"
        const val UPLOADED_ID = "up-1"
        const val FILE_ID = "file-1"
        const val LOCAL_FILE_PATH = "/tmp/photo.jpg"
        val TIMEOUT = 60.seconds
    }
}
