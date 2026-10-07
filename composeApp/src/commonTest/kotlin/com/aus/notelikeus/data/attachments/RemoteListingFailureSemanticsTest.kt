package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.remote.AttachmentRemoteMetadata
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.remote.OperationRemoteIdentity
import com.aus.notelikeus.data.sync.FakeNoteDao
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.LocalCommitResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * F-11: a remote listing failure is not an authoritative empty listing.
 *
 * The attachment sweep asks the server which rows are pending deletion, then deletes those objects and
 * purges their metadata. That listing is a *protected remote read*, so it can fail for reasons that say
 * nothing about the account's data — no network, an expired capture, a 5xx, a malformed payload. Reporting
 * such a failure as `Applied` told the caller the sweep had run and found nothing to do, which is a claim
 * the server never made: the sweep silently never ran, and the run reported success around it.
 *
 * The distinction these lanes pin is between
 *
 *   "the server says nothing is pending"  -> a successful no-op sweep
 *   "the server could not be asked"       -> a failure the caller must see
 *
 * and a failure is never allowed to become an empty result that other decisions are then made from.
 */
class RemoteListingFailureSemanticsTest {

    private fun fixture(
        listing: suspend (OperationRemoteIdentity) -> List<PendingDeletedAttachment>,
    ): Triple<AttachmentSyncService, FakeNoteDao, RecordingMetadata> {
        val noteDao = FakeNoteDao()
        val metadata = RecordingMetadata(listing)
        val service = AttachmentSyncService(
            blobTransport = RecordingBlobTransport(),
            metadata = metadata,
            localStorage = NoLocalStorage(),
            noteDao = noteDao,
            staging = NoStagingStore(),
            ownerIdProvider = { OWNER },
            attachmentsEnabled = { true },
        )
        return Triple(service, noteDao, metadata)
    }

    private fun token() = LocalCommitGate.capture(OWNER)!!

    /** LIST-1: a failed listing is not reported as a successful no-op sweep. */
    @Test
    fun `LIST-1 a remote listing failure does not become a successful empty sweep`() = runTest(timeout = TIMEOUT) {
        val (service, _, _) = fixture { error("upstream database unreachable") }

        val failure = runCatching {
            service.sweepPendingDeletedAttachments(token(), OperationRemoteIdentity(OWNER, TOKEN))
        }

        assertTrue(
            failure.isFailure,
            "a failed listing was reported as ${failure.getOrNull()}: a sweep that never ran",
        )
    }

    /** LIST-2: a successful empty listing is still an authoritative empty sweep. */
    @Test
    fun `LIST-2 a successful empty listing remains a successful sweep`() = runTest(timeout = TIMEOUT) {
        val (service, _, metadata) = fixture { emptyList() }

        val outcome = service.sweepPendingDeletedAttachments(token(), OperationRemoteIdentity(OWNER, TOKEN))

        assertTrue(outcome is LocalCommitResult.Applied, "an empty listing reported $outcome")
        assertEquals(1, metadata.listingCalls, "the listing did not run")
        assertEquals(0, metadata.purges, "an empty listing purged something")
    }

    /** LIST-3/§8: an authentication refusal is a failure, not an empty listing. */
    @Test
    fun `LIST-3 an authentication failure is not converted to empty`() = runTest(timeout = TIMEOUT) {
        val (service, _, metadata) = fixture { throw AuthenticationRefused() }

        val failure = runCatching {
            service.sweepPendingDeletedAttachments(token(), OperationRemoteIdentity(OWNER, TOKEN))
        }

        assertTrue(failure.isFailure, "an authentication refusal was reported as ${failure.getOrNull()}")
        assertEquals(0, metadata.purges, "a refused listing still purged rows")
    }

    /** LIST-4/§9: a timeout or 5xx is a failure, not an empty listing. */
    @Test
    fun `LIST-4 a timeout or 5xx is not converted to empty`() = runTest(timeout = TIMEOUT) {
        val (service, _, _) = fixture { error("503 Service Unavailable") }

        val failure = runCatching {
            service.sweepPendingDeletedAttachments(token(), OperationRemoteIdentity(OWNER, TOKEN))
        }

        assertTrue(failure.isFailure, "a 5xx was reported as ${failure.getOrNull()}")
    }

    /** LIST-5/§14: a failed listing cannot drive reconciliation, and local rows survive it. */
    @Test
    fun `LIST-5 a failed listing leaves local state alone`() = runTest(timeout = TIMEOUT) {
        val (service, noteDao, _) = fixture { error("connection reset") }
        noteDao.notes[42L] = noteWithAttachment().toNoteEntityForTest()

        runCatching { service.sweepPendingDeletedAttachments(token(), OperationRemoteIdentity(OWNER, TOKEN)) }
        runCatching {
            service.hydrateAllNotes(token(), OperationRemoteIdentity(OWNER, TOKEN))
        }

        assertEquals(1, noteDao.notes.size, "local notes were removed by a read that failed")
        assertEquals(
            1,
            noteDao.notes[42L]?.let { 1 } ?: 0,
            "the local note was cleared or replaced because the remote listing was unavailable",
        )
    }

    /** LIST-6: a successful non-empty listing behaves exactly as before. */
    @Test
    fun `LIST-6 a successful non-empty listing still sweeps`() = runTest(timeout = TIMEOUT) {
        val (service, _, metadata) = fixture { listOf(PendingDeletedAttachment("att-1", "42", "owners/x")) }

        val outcome = service.sweepPendingDeletedAttachments(token(), OperationRemoteIdentity(OWNER, TOKEN))

        assertTrue(outcome is LocalCommitResult.Applied, "a non-empty listing reported $outcome")
        assertTrue(metadata.listingCalls >= 1, "the listing did not run")
        assertEquals(1, metadata.purges, "the pending row was not purged")
    }

    /** LIST-7/§13: the sweep's caller cannot report success after a required listing failure. */
    @Test
    fun `LIST-7 a required listing failure cannot produce a successful outcome`() = runTest(timeout = TIMEOUT) {
        val (service, _, _) = fixture { error("upstream database unreachable") }

        val reported = runCatching {
            service.sweepPendingDeletedAttachments(token(), OperationRemoteIdentity(OWNER, TOKEN))
        }.getOrNull()

        assertTrue(
            reported !is LocalCommitResult.Applied,
            "the failure was reported to the caller as a successful outcome: $reported",
        )
    }

    /** LIST-8: the next attempt is unaffected — nothing about the failure is sticky. */
    @Test
    fun `LIST-8 a later attempt succeeds normally`() = runTest(timeout = TIMEOUT) {
        var attempt = 0
        val (service, _, metadata) = fixture {
            attempt++
            if (attempt == 1) error("network timeout") else emptyList()
        }
        val identity = OperationRemoteIdentity(OWNER, TOKEN)

        val first = runCatching { service.sweepPendingDeletedAttachments(token(), identity) }
        val second = service.sweepPendingDeletedAttachments(token(), identity)

        assertTrue(first.isFailure, "the first attempt did not fail")
        assertTrue(second is LocalCommitResult.Applied, "the retry reported $second")
        assertEquals(2, metadata.listingCalls, "the retry did not re-issue the listing")
    }

    private fun noteWithAttachment() = Note(
        id = 42L,
        title = "note",
        content = "body",
        timestamp = 1L,
        color = 0,
        attachments = listOf(
            Attachment(
                id = "att-local",
                noteId = 42L,
                storagePath = pendingStoragePath("att-local"),
                type = "image",
                mimeType = "image/png",
                sizeBytes = 3,
            ),
        ),
    )

    private class AuthenticationRefused : Exception("unauthenticated")

    /** Records what the service asked the remote, and answers from the scripted listing. */
    private class RecordingMetadata(
        private val listing: suspend (OperationRemoteIdentity) -> List<PendingDeletedAttachment>,
    ) : AttachmentRemoteMetadata {
        var listingCalls = 0
        var purges = 0

        override suspend fun listPendingDeletedAttachments(
            identity: OperationRemoteIdentity,
        ): List<PendingDeletedAttachment> {
            listingCalls++
            return listing(identity)
        }

        override suspend fun listUserAttachments(identity: OperationRemoteIdentity): List<NoteAttachmentMetadata> =
            throw AuthenticationRefused()

        override suspend fun register(identity: OperationRemoteIdentity, metadata: NoteAttachmentMetadata) = Unit

        override suspend fun delete(identity: OperationRemoteIdentity, attachmentId: String, noteId: String) = Unit

        override suspend fun purgeDeleted(
            identity: OperationRemoteIdentity,
            attachmentId: String,
            noteId: String,
        ) {
            purges++
        }
    }

    private class RecordingBlobTransport : AttachmentBlobTransport {
        override suspend fun captureContext() = AttachmentRemoteContext(OWNER, TOKEN)

        override suspend fun upload(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
            bytes: ByteArray,
            mimeType: String,
        ) = com.aus.notelikeus.data.remote.AttachmentBlobUploadResult("owners/$OWNER/notes/$noteId/$attachmentId", 1L, mimeType)

        override suspend fun download(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
        ): ByteArray = ByteArray(0)

        override suspend fun delete(context: AttachmentRemoteContext, noteId: String, attachmentId: String) = Unit
    }

    private class NoLocalStorage : AttachmentLocalStorage {
        override fun persistImageBytes(bytes: ByteArray, extension: String): String? = null

        override fun readBytes(storagePath: String): ByteArray? = null

        override fun deleteIfLocal(storagePath: String) = Unit
    }

    private class NoStagingStore : AttachmentStagingStore {
        override suspend fun stage(
            attachmentId: String,
            ownerId: String,
            noteId: Long?,
            bytes: ByteArray,
            mimeType: String,
        ): StagedAttachment? = null

        override suspend fun readBytes(attachmentId: String, ownerId: String): ByteArray? = null

        override suspend fun metadata(attachmentId: String, ownerId: String): StagedAttachment? = null

        override suspend fun isStaged(attachmentId: String, ownerId: String): Boolean = false

        override suspend fun bindNote(attachmentId: String, ownerId: String, noteId: Long) = Unit

        override suspend fun release(attachmentId: String, ownerId: String) = Unit

        override suspend fun list(ownerId: String): List<StagedAttachment> = emptyList()
    }

    private companion object {
        const val OWNER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val TOKEN = "token-a"
        val TIMEOUT = 60.seconds
    }
}

private fun Note.toNoteEntityForTest() = toNoteEntity()
