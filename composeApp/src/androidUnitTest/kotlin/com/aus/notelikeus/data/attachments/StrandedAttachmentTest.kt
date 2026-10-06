package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentBlobUploadResult
import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.LocalCommitResult
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What a note does with an attachment whose bytes cannot be read.
 *
 * A reference with no bytes behind it uploads nothing and renders as a broken image, and the note
 * carries it forever. Discarding it is right when the bytes are genuinely gone — but `readBytes`
 * answers `null` both for "not there" and for "could not read it this time", and treating those
 * alike would let a failing disk, or an encrypted store that happens to be locked, quietly delete
 * a picture out of someone's note. So absence has to be proven, not inferred from a failed read.
 */
class StrandedAttachmentTest {

    @get:Rule
    val temp = TemporaryFolder()

    private object NoopTransport : AttachmentBlobTransport {
        override suspend fun captureContext() =
            AttachmentRemoteContext(
                ownerId = "owner-1",
                accessToken = "token-owner-1",
            )

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
        ) = ByteArray(0)

        override suspend fun delete(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
        ) {}
    }

    /** Real staging on a temp dir, so "gone" means genuinely absent from the filesystem. */
    private fun realStaging() = FileAttachmentStagingStore(
        root = temp.root.absolutePath.toPath(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    /** Staging that has the bytes but cannot read or answer for them — a store under duress. */
    private fun unreadableStaging(delegate: AttachmentStagingStore) =
        object : AttachmentStagingStore by delegate {
            override suspend fun readBytes(attachmentId: String, ownerId: String): ByteArray? = null
            override suspend fun metadata(attachmentId: String, ownerId: String): StagedAttachment? = null
            override suspend fun isStaged(attachmentId: String, ownerId: String): Boolean? = null
        }

    private fun service(staging: AttachmentStagingStore) = AttachmentSyncService(
        blobTransport = NoopTransport,
        metadata = null,
        localStorage = mockk(relaxed = true),
        noteDao = mockk<NoteDao>(relaxed = true),
        staging = staging,
        ownerIdProvider = { "owner-1" },
        attachmentsEnabled = { true },
    )

    private fun pendingAttachment(id: String) = Attachment(
        id = id,
        noteId = 1L,
        storagePath = pendingStoragePath(id),
        type = "image",
    )

    private fun noteWith(vararg attachments: Attachment) = Note(
        id = 1L,
        title = "n",
        content = "",
        timestamp = 1L,
        color = 0,
        attachments = attachments.toList(),
    )

    @Test
    fun `an attachment whose staged bytes are gone is dropped from the note`() = runTest {
        val note = noteWith(pendingAttachment("gone-1"))

        val synced = service(realStaging()).uploadUnderLiveToken(note)

        assertEquals(
            "a reference nothing can ever satisfy should not stay on the note",
            emptyList<Attachment>(),
            synced.attachments,
        )
    }

    /**
     * The case that makes proving absence worth the trouble. The bytes are staged and fine; the
     * store simply cannot answer right now. Dropping here would destroy the picture.
     */
    @Test
    fun `an attachment whose store cannot answer is kept`() = runTest {
        val staging = realStaging()
        staging.stage(
            attachmentId = "held-1",
            ownerId = "owner-1",
            noteId = 1L,
            bytes = ByteArray(8) { 7 },
            mimeType = "image/jpeg",
        )
        val note = noteWith(pendingAttachment("held-1"))

        val synced = service(unreadableStaging(staging)).uploadUnderLiveToken(note)

        assertEquals(
            "an unreadable store must not delete the user's picture",
            listOf("held-1"),
            synced.attachments.map { it.id },
        )
    }

    @Test
    fun `dropping one stranded attachment leaves the others alone`() = runTest {
        val staging = realStaging()
        staging.stage(
            attachmentId = "kept-1",
            ownerId = "owner-1",
            noteId = 1L,
            bytes = ByteArray(4) { 1 },
            mimeType = "image/jpeg",
        )
        val note = noteWith(pendingAttachment("gone-1"), pendingAttachment("kept-1"))

        val synced = service(staging).uploadUnderLiveToken(note)

        assertEquals(listOf("kept-1"), synced.attachments.map { it.id })
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
}
