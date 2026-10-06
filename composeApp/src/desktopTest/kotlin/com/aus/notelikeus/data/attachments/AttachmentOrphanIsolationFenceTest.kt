package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentBlobUploadResult
import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.remote.OperationRemoteIdentity
import com.aus.notelikeus.data.remote.SupabaseNoteTransport
import com.aus.notelikeus.data.remote.SupabaseRpcClient
import com.aus.notelikeus.data.sync.FakeNoteDao
import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.LocalCommitResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Which attachment ids a note commit names, and which it must never name — R19.2.
 *
 * The backend (see `notelikeus_attachment_commitment.test.sql`) owns the visibility rule: a row is
 * provisional until a successful note commit names it, and hydration sees committed rows only. What the
 * client owns is *which* ids it names, and *when*: a commit describes the note version it is pushing, so
 * it may only name that version's remote attachments — and a continuation whose dataset has been
 * replaced must not commit anything at all, because the upload it is finishing belongs to a note this
 * dataset no longer is.
 *
 * These lanes drive the real `NoteSyncEngine` and the real `SupabaseNoteTransport`, with a recording RPC
 * client, so the argument set that would reach `apply_note_change` is what is asserted.
 */
class AttachmentOrphanIsolationFenceTest {

    private var noteDao = FakeNoteDao()
    private var stateStore = FakeNoteSyncStateStore()
    private lateinit var rpc: RecordingRpc
    private lateinit var blob: RecordingBlobTransport
    private lateinit var staging: MemoryStagingStore

    private fun setUpFixture() {
        noteDao = FakeNoteDao()
        stateStore = FakeNoteSyncStateStore().apply { setLastMergedUserId(OWNER) }
        rpc = RecordingRpc()
        blob = RecordingBlobTransport()
        staging = MemoryStagingStore()
    }

    /**
     * R2ORPH-4 / §37: the commit names exactly the remote attachments of the version it pushes.
     *
     * This is the client end of the promotion contract: the backend promotes exactly the ids it is
     * named (see `notelikeus_attachment_commitment.test.sql`), so the client's selection rule is what
     * decides which rows may ever leave the provisional state.
     */
    @Test
    fun `R2ORPH-4 a note commit names exactly its remote attachments`() = runTest(timeout = TIMEOUT) {
        setUpFixture()

        SupabaseNoteTransport(rpc).putNotes(
            OperationRemoteIdentity(OWNER, TOKEN),
            listOf(noteWith(REMOTE_ID, PENDING_ID, FILE_ID)),
        )

        assertEquals(1, rpc.noteCommitArgs.size, "the note batch did not commit exactly one note")
        val named = rpc.noteCommitArgs.single()
        assertTrue(named.contains(REMOTE_ID), "the remote attachment was not named, so it stays invisible")
        assertTrue(!named.contains(PENDING_ID), "a staged attachment was named: $named")
        assertTrue(!named.contains(FILE_ID), "a local file attachment was named: $named")
    }

    /** R2ORPH-3 / §14: with nothing remote on the note, the commit names nothing at all. */
    @Test
    fun `R2ORPH-3 a note with no remote attachment names no ids`() = runTest(timeout = TIMEOUT) {
        setUpFixture()

        SupabaseNoteTransport(rpc).putNotes(
            OperationRemoteIdentity(OWNER, TOKEN),
            listOf(noteWith(PENDING_ID)),
        )

        val named = rpc.noteCommitArgs.singleOrNull()
        assertTrue(named != null, "the note batch did not commit")
        assertTrue(!named.contains(PENDING_ID), "a staged attachment was named: $named")
        assertTrue(named == "[]", "a note with no remote attachment named ids: $named")
    }

    /**
     * R2ORPH-1 / §17: an upload whose dataset is replaced behind it may physically finish (F-10), and
     * the continuation it belongs to is refused at the gate.
     *
     * The engine-level half of this — an upload that completed across the boundary does not carry the
     * note batch with it — is `AttachmentUploadRemoteStartFenceTest`'s AURSF-5, which drives the same
     * boundary through `uploadAllNotes`.
     */
    @Test
    fun `R2ORPH-1 an upload that finished behind a boundary cannot be committed by it`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            seedStaged(PENDING_ID)
            blob.parkUpload(number = 1)
            val token = LocalCommitGate.capture(OWNER)

            val upload = async(Dispatchers.IO) {
                runCatching { service().syncNoteAttachments(noteWith(PENDING_ID), token) }
            }
            blob.awaitUploadParked()

            withContext(Dispatchers.IO) { LocalCommitGate.isolate { } }
            blob.releaseUpload()
            val outcome = upload.await()

            assertTrue(outcome.isSuccess, "the refusal surfaced as a failure: ${outcome.exceptionOrNull()}")
            assertEquals(1, blob.uploads.size, "the already-issued upload must still physically complete")
            assertEquals(
                LocalCommitResult.StaleGeneration,
                LocalCommitGate.commit(token) { },
                "the replaced dataset could still commit the note that would reference the upload",
            )
        }

    // ---- fixtures ----

    private fun noteWith(vararg attachmentIds: String) = Note(
        id = NOTE_ID,
        title = "note",
        content = "body",
        timestamp = 1_000L,
        color = 0,
        attachments = attachmentIds.map { attachment(it) },
    )

    private fun seedStaged(attachmentId: String) {
        staging.seed(attachmentId, OWNER, NOTE_ID, byteArrayOf(1, 2, 3), "image/png")
    }

    private fun service() = AttachmentSyncService(
        blobTransport = blob,
        metadata = null,
        localStorage = NoopLocalStorage(),
        noteDao = noteDao,
        staging = staging,
        ownerIdProvider = { OWNER },
        attachmentsEnabled = { true },
    )

    private fun attachment(id: String) = Attachment(
        id = id,
        noteId = NOTE_ID,
        storagePath = when (id) {
            PENDING_ID -> pendingStoragePath(id)
            FILE_ID -> fileStoragePath("/tmp/$id.jpg")
            else -> "$ATTACHMENT_R2_PREFIX owners/$OWNER/notes/$NOTE_ID/$id"
        },
        type = "image",
        mimeType = "image/png",
        sizeBytes = 3,
    )

    /** Records every note commit's attachment-id argument, as the RPC would receive it. */
    private class RecordingRpc : SupabaseRpcClient {
        val noteCommitArgs = mutableListOf<String>()

        override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject = JsonObject(emptyMap())

        override suspend fun callRpc(
            identity: OperationRemoteIdentity,
            functionName: String,
            body: JsonObject,
        ): JsonObject {
            if (functionName == "apply_note_change") {
                noteCommitArgs += body["p_attachment_ids"]?.toString().orEmpty()
            }
            if (functionName == "apply_note_change") {
                return JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("applied"),
                        "revision" to JsonPrimitive(1),
                        "server_updated_at" to JsonPrimitive(1L),
                    ),
                )
            }
            return JsonObject(emptyMap())
        }

        override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
            JsonArray(emptyList())

        override suspend fun callRpcElement(
            identity: OperationRemoteIdentity,
            functionName: String,
            body: JsonObject,
        ): JsonElement = JsonArray(emptyList())
    }

    private class RecordingBlobTransport : AttachmentBlobTransport {
        val uploads = mutableListOf<String>()
        private var parkUploadNumber: Int? = null
        private val parked = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        fun parkUpload(number: Int) {
            parkUploadNumber = number
        }

        suspend fun awaitUploadParked() = parked.await()

        fun releaseUpload() {
            released.complete(Unit)
        }

        override suspend fun captureContext() = AttachmentRemoteContext(ownerId = OWNER, accessToken = TOKEN)

        override suspend fun upload(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
            bytes: ByteArray,
            mimeType: String,
        ): AttachmentBlobUploadResult {
            uploads += attachmentId
            if (parkUploadNumber == uploads.size) {
                parked.complete(Unit)
                released.await()
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

        override suspend fun delete(context: AttachmentRemoteContext, noteId: String, attachmentId: String) {}
    }

    private class MemoryStagingStore : AttachmentStagingStore {
        private val bytes = mutableMapOf<String, ByteArray>()
        private val meta = mutableMapOf<String, StagedAttachment>()

        fun seed(attachmentId: String, ownerId: String, noteId: Long?, data: ByteArray, mimeType: String) {
            bytes[key(attachmentId, ownerId)] = data
            meta[key(attachmentId, ownerId)] = StagedAttachment(
                attachmentId = attachmentId,
                ownerId = ownerId,
                noteId = noteId,
                mimeType = mimeType,
                sizeBytes = data.size.toLong(),
                createdAt = 1L,
            )
        }

        private fun key(attachmentId: String, ownerId: String) = "$ownerId/$attachmentId"

        override suspend fun stage(
            attachmentId: String,
            ownerId: String,
            noteId: Long?,
            bytes: ByteArray,
            mimeType: String,
        ): StagedAttachment? {
            seed(attachmentId, ownerId, noteId, bytes, mimeType)
            return meta[key(attachmentId, ownerId)]
        }

        override suspend fun readBytes(attachmentId: String, ownerId: String): ByteArray? =
            bytes[key(attachmentId, ownerId)]

        override suspend fun metadata(attachmentId: String, ownerId: String): StagedAttachment? =
            meta[key(attachmentId, ownerId)]

        override suspend fun isStaged(attachmentId: String, ownerId: String): Boolean =
            bytes.containsKey(key(attachmentId, ownerId))

        override suspend fun bindNote(attachmentId: String, ownerId: String, noteId: Long) {}

        override suspend fun release(attachmentId: String, ownerId: String) {
            bytes.remove(key(attachmentId, ownerId))
            meta.remove(key(attachmentId, ownerId))
        }

        override suspend fun list(ownerId: String): List<StagedAttachment> = emptyList()
    }

    private class NoopLocalStorage : AttachmentLocalStorage {
        override fun persistImageBytes(bytes: ByteArray, extension: String): String? = null

        override fun readBytes(storagePath: String): ByteArray? = null

        override fun deleteIfLocal(storagePath: String) {}
    }

    private companion object {
        const val OWNER = "owner-a"
        const val TOKEN = "token-a"
        const val NOTE_ID = 42L
        const val REMOTE_ID = "att-remote"
        const val PENDING_ID = "att-pending"
        const val FILE_ID = "att-file"
        const val NOW = 2_000_000_000_000L

        val TIMEOUT = 60.seconds
    }
}
