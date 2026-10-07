package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentBlobUploadResult
import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.remote.OperationRemoteIdentity
import com.aus.notelikeus.data.remote.SupabaseAttachmentMetadata
import com.aus.notelikeus.data.remote.SupabaseRpcClient
import com.aus.notelikeus.data.sync.FakeCloudNoteTransport
import com.aus.notelikeus.data.sync.FakeLabelDao
import com.aus.notelikeus.data.sync.FakeNoteDao
import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.testIdentity
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What a token with **no** originating account may delete — R18.1.
 *
 * R18 gives both token-aware blob-delete families an owner/context coherence check, but that check
 * deliberately returns early when `commitToken.initiatingUid == null`, because the *upload* path needs
 * the null-UID shape for guest→first-account adoption. For deletion that bypass was unproven: a guest
 * token whose operation is adopted into the first signed-in account keeps the **same generation** (no
 * isolation is crossed), so every generation check passes, and the shared helper would wave the
 * deletion through against whatever blob context the now-signed-in session supplies.
 *
 * These lanes establish what is actually reachable and pin the rule: remote deletion needs an
 * authoritative originating account. A guest-origin removal still performs its local cleanup; a
 * `pending:` reference is skipped without a grant or a call (its only possible server artefact is a
 * metadata row, and the sweep owns that); an `r2:` reference — which a guest-origin operation cannot
 * legitimately hold, since a remote object exists only because a signed-in account uploaded it, and a
 * sign-out wipes the local rows that reference one — is an invariant failure. The upload path is
 * untouched, so legitimate adoption keeps working.
 *
 * Deterministic `CompletableDeferred` barriers only, no sleeps.
 */
class AttachmentDeleteGuestAdoptionIdentityTest {

    private var sessionOwner: String? = null
    private lateinit var blob: RecordingBlobTransport
    private lateinit var rpc: RecordingMetadataRpc
    private lateinit var staging: MemoryStagingStore
    private var noteDao = FakeNoteDao()

    private fun setUpFixture() {
        sessionOwner = null
        blob = RecordingBlobTransport()
        rpc = RecordingMetadataRpc()
        staging = MemoryStagingStore()
        noteDao = FakeNoteDao()
    }

    // ---- GDEL-1: adoption keeps the generation, which is what makes the question live ----

    @Test
    fun `GDEL-1 adoption does not move the generation, so generation checks cannot see it`() {
        setUpFixture()
        val generation = LocalCommitGate.currentGeneration()

        val guestToken = LocalCommitGate.captureCoherent { sessionOwner }
        sessionOwner = UID_A
        val adoptedToken = LocalCommitGate.captureCoherent { sessionOwner }

        assertNull(guestToken.initiatingUid, "precondition: the guest token has no account")
        assertEquals(UID_A, adoptedToken.initiatingUid, "precondition: the adopted token has one")
        assertEquals(
            guestToken.generation,
            adoptedToken.generation,
            "guest->first-account adoption must not move the generation, or a stale-generation refusal " +
                "would hide the null-origin identity question instead of exposing it",
        )
        assertEquals(generation, LocalCommitGate.currentGeneration(), "precondition: nothing isolated")
    }

    // ---- GDEL-2: the adversarial composition, in the only shape production can produce ----

    @Test
    fun `GDEL-2 a guest-origin removal adopted mid-capture deletes nothing remotely`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            // A guest stages bytes: staging works signed out, so a `pending:` reference on a guest note
            // is a state production really produces. (An `r2:` reference is not — see GDEL-2b.)
            staging.seed(ATT_A, UID_A, NOTE_ID, byteArrayOf(1, 2, 3), "image/png")
            blob.parkCapture(number = 1)
            val guestToken = LocalCommitGate.captureCoherent { sessionOwner }
            val generationBefore = LocalCommitGate.currentGeneration()

            val removal = async(Dispatchers.IO) {
                service().deleteAttachmentsForNote(NOTE_ID, listOf(pending(ATT_A)), guestToken)
            }
            blob.awaitCaptureParked()

            // The adoption: the device's first account signs in while the capture is in flight. No
            // isolation is crossed, so the generation does not move, and the capture returns A's context.
            sessionOwner = UID_A
            blob.context = AttachmentRemoteContext(ownerId = UID_A, accessToken = TOKEN_A)
            assertEquals(
                generationBefore,
                LocalCommitGate.currentGeneration(),
                "precondition: adoption left the generation alone",
            )
            blob.releaseCapture()
            val result = removal.await()

            assertEquals(LocalCommitResult.Applied(Unit), result, "the guest removal surfaced as a failure")
            assertEquals(
                emptyList(),
                blob.deletes.map { it.toString() },
                "a deletion ran for a guest-origin operation that inherited the signed-in account's " +
                    "blob identity",
            )
            assertNull(
                staging.readBytes(ATT_A, UID_A),
                "the local cleanup the user asked for did not happen",
            )
            assertEquals(
                generationBefore,
                LocalCommitGate.currentGeneration(),
                "precondition: the operation never crossed a boundary",
            )
        }

    @Test
    fun `GDEL-2b a null origin can never delete a remote object`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        // Not a state production can produce (a sign-out wipes the rows that reference an `r2:` object,
        // and a guest cannot upload), which is exactly why it must fail loudly rather than quietly
        // delete: any future route that pairs a null origin with a remote object surfaces here.
        val guestToken = LocalCommitGate.captureCoherent { sessionOwner }

        val outcome = runCatching {
            service().deleteAttachmentsForNote(NOTE_ID, listOf(r2(ATT_A)), guestToken)
        }

        assertTrue(outcome.isFailure, "a null origin deleted a remote object: ${outcome.getOrNull()}")
        assertIs<IllegalStateException>(
            outcome.exceptionOrNull(),
            "the refusal was reported as something other than an invariant failure: ${outcome.exceptionOrNull()}",
        )
        assertEquals(emptyList(), blob.deletes, "a remote object was deleted without an originating account")
    }

    // ---- GDEL-3: the sweep, which can only run for an account ----

    @Test
    fun `GDEL-3 a sweep with no originating account refuses before any remote call`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            rpc.pending += NOTE_ID.toString() to ATT_A
            val guestToken = LocalCommitGate.captureCoherent { sessionOwner }

            val outcome = runCatching {
                service().sweepPendingDeletedAttachments(guestToken, testIdentity(UID_A))
            }

            assertTrue(outcome.isFailure, "a sweep without an originating account ran: ${outcome.getOrNull()}")
            assertIs<IllegalStateException>(
                outcome.exceptionOrNull(),
                "the refusal was reported as something other than an invariant failure",
            )
            assertEquals(emptyList(), rpc.calls, "the sweep read the server before saying whose rows these are")
            assertEquals(emptyList(), blob.deletes, "the sweep deleted without an originating account")
        }

    @Test
    fun `GDEL-3b a signed-out download cannot reach the sweep at all`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        rpc.pending += NOTE_ID.toString() to ATT_A
        // `uidProvider().getOrThrow()` is what production wires to `ensureSignedIn()`, so a signed-out
        // download stops before the tail — a second, independent reason the sweep never sees a null
        // origin.
        val engine = NoteSyncEngine(
            transport = FakeCloudNoteTransport(),
            remoteIdentityProvider = com.aus.notelikeus.data.remote.RemoteIdentityProvider(
                accessTokenProvider = { null },
                sessionOwnerId = { null },
            ),
            noteDao = noteDao,
            labelDao = FakeLabelDao(),
            syncStateStore = FakeNoteSyncStateStore(),
            uidProvider = { Result.failure(IllegalStateException("not signed in")) },
            platform = "desktop",
            now = { NOW },
            attachmentSync = service(),
        )

        val result = engine.downloadAllNotes()

        assertTrue(result.isFailure, "a signed-out download reported success")
        assertEquals(emptyList(), rpc.calls, "a signed-out download issued a metadata call")
        assertEquals(emptyList(), blob.deletes, "a signed-out download deleted a remote object")
    }

    // ---- GDEL-4: no grant before ownership exists ----

    @Test
    fun `GDEL-4 the null-origin rule is judged before any deletion grant`() {
        val source = serviceSource()
        val body = methodBodyCarrying(source, "deleteAttachmentsForNote", "commitToken")
        assertTrue(body.isNotEmpty(), "precondition: the token-aware overload was located")

        // The decision itself lives in the helper, and the loop asks it before it takes any grant.
        val decision = body.indexOf("mayDeleteRemotely(")
        val grant = body.indexOf("authorizeRemoteMutationStart(")
        assertTrue(decision >= 0, "the token-aware deletion no longer asks whether the origin may delete")
        assertTrue(
            grant < 0 || decision < grant,
            "a deletion grant is created before the operation's origin is established",
        )

        val helper = bodyOf(source, "mayDeleteRemotely")
        assertTrue(helper.isNotEmpty(), "precondition: the origin helper was located")
        assertTrue(
            helper.contains("initiatingUid != null"),
            "the origin helper no longer tells a null origin apart from an account origin",
        )
        assertTrue(
            helper.contains("check(!isR2Attachment"),
            "a null origin is no longer refused for a remote object",
        )
        assertTrue(
            helper.indexOf("initiatingUid != null") < helper.indexOf("check(!isR2Attachment"),
            "the remote-object invariant is judged before the account decision it belongs to",
        )

        val sweep = methodBodyCarrying(source, "sweepPendingDeletedAttachments", "commitToken")
        val sweepGuard = sweep.indexOf("commitToken.initiatingUid != null")
        val listing = sweep.indexOf("listPendingDeletedAttachments(")
        assertTrue(sweepGuard >= 0, "the sweep no longer requires an originating account")
        assertTrue(
            listing < 0 || sweepGuard < listing,
            "the sweep reads the server before establishing whose rows it is reading",
        )
    }

    /** The body of a non-suspend private member, for the origin helper. */
    private fun bodyOf(source: String, name: String): String {
        val declaration = source.indexOf("fun $name(")
        if (declaration < 0) return ""
        val open = source.indexOf('{', declaration)
        return if (open < 0) "" else source.substring(open, matchingBrace(source, open))
    }

    // ---- GDEL-5: the intentional guest shape is untouched on the upload side ----

    @Test
    fun `GDEL-5 a guest-shaped upload still runs, because adoption depends on it`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            staging.seed(ATT_A, GUEST_OWNER, NOTE_ID, byteArrayOf(1, 2, 3), "image/png")
            val guestToken = LocalCommitGate.captureCoherent { sessionOwner }

            val result = service().syncNoteAttachments(noteWith(pending(ATT_A)), guestToken)

            assertIs<LocalCommitResult.Applied<Note>>(
                result,
                "the shared upload helper no longer accepts the guest shape",
            )
            assertEquals(1, blob.uploads.size, "the guest-shaped upload never reached the transport")
            assertTrue(
                blob.uploads.single().attachmentId == ATT_A,
                "the upload did not carry the staged attachment",
            )
        }

    // ---- GDEL-6: signed-in deletion behaviour is unchanged ----

    @Test
    fun `GDEL-6 a signed-in operation still deletes remotely, as R18 pinned`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        sessionOwner = UID_A
        staging.seed(ATT_A, UID_A, NOTE_ID, byteArrayOf(1, 2, 3), "image/png")
        val token = LocalCommitGate.captureCoherent { sessionOwner }

        val result = service().deleteAttachmentsForNote(NOTE_ID, listOf(pending(ATT_A)), token)

        assertEquals(LocalCommitResult.Applied(Unit), result, "a valid signed-in removal was refused")
        assertEquals(
            listOf(UID_A),
            blob.deletes.map { it.ownerId },
            "the signed-in removal stopped deleting remotely",
        )
    }

    // ---- fixtures ----

    private fun service() = AttachmentSyncService(
        blobTransport = blob,
        metadata = SupabaseAttachmentMetadata(rpc),
        localStorage = NoopLocalStorage(),
        noteDao = noteDao,
        staging = staging,
        ownerIdProvider = { sessionOwner },
        attachmentsEnabled = { true },
    )

    private fun noteWith(vararg attachments: Attachment) = Note(
        id = NOTE_ID,
        title = "note",
        content = "body",
        timestamp = 1_000L,
        color = 0,
        attachments = attachments.toList(),
    )

    private fun pending(attachmentId: String) = Attachment(
        id = attachmentId,
        noteId = NOTE_ID,
        storagePath = pendingStoragePath(attachmentId),
        type = "image",
        mimeType = "image/png",
        sizeBytes = 3,
    )

    private fun r2(attachmentId: String) = Attachment(
        id = attachmentId,
        noteId = NOTE_ID,
        storagePath = "$ATTACHMENT_R2_PREFIX owners/$UID_A/notes/$NOTE_ID/$attachmentId",
        type = "image",
        mimeType = "image/png",
        sizeBytes = 3,
    )

    private class RecordingBlobTransport : AttachmentBlobTransport {
        data class Deletion(
            val noteId: String,
            val attachmentId: String,
            val ownerId: String,
            val accessToken: String,
        )

        data class Upload(val attachmentId: String, val ownerId: String)

        val captures = mutableListOf<AttachmentRemoteContext>()
        val deletes = mutableListOf<Deletion>()
        val uploads = mutableListOf<Upload>()
        var context: AttachmentRemoteContext = AttachmentRemoteContext(ownerId = UID_A, accessToken = TOKEN_A)

        private var parkCaptureNumber: Int? = null
        private val captureParked = CompletableDeferred<Unit>()
        private val captureReleased = CompletableDeferred<Unit>()

        fun parkCapture(number: Int) {
            parkCaptureNumber = number
        }

        suspend fun awaitCaptureParked() = captureParked.await()

        fun releaseCapture() {
            captureReleased.complete(Unit)
        }

        override suspend fun captureContext(): AttachmentRemoteContext {
            captures += context
            if (parkCaptureNumber == captures.size) {
                captureParked.complete(Unit)
                captureReleased.await()
            }
            return context
        }

        override suspend fun upload(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
            bytes: ByteArray,
            mimeType: String,
        ): AttachmentBlobUploadResult {
            uploads += Upload(attachmentId, context.ownerId)
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
        ): ByteArray = error("no download expected in this suite")

        override suspend fun delete(context: AttachmentRemoteContext, noteId: String, attachmentId: String) {
            deletes += Deletion(noteId, attachmentId, context.ownerId, context.accessToken)
        }
    }

    private class RecordingMetadataRpc : SupabaseRpcClient {
        val pending = mutableListOf<Pair<String, String>>()
        val calls = mutableListOf<String>()

        override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject = JsonObject(emptyMap())

        override suspend fun callRpc(
            identity: OperationRemoteIdentity,
            functionName: String,
            body: JsonObject,
        ): JsonObject {
            calls += functionName
            return JsonObject(emptyMap())
        }

        override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
            JsonArray(emptyList())

        override suspend fun callRpcElement(
            identity: OperationRemoteIdentity,
            functionName: String,
            body: JsonObject,
        ): JsonElement {
            calls += functionName
            return JsonArray(
                pending.map { (noteId, attachmentId) ->
                    buildJsonObject {
                        put("attachment_id", attachmentId)
                        put("note_id", noteId)
                        put("object_key", "owners/$UID_A/notes/$noteId/$attachmentId")
                    }
                },
            )
        }
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

    private fun serviceSource(): String {
        val candidates = listOf(
            File("src/commonMain/kotlin/com/aus/notelikeus/data/attachments/AttachmentSyncService.kt"),
            File("composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/attachments/AttachmentSyncService.kt"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("could not read AttachmentSyncService.kt from ${candidates.joinToString { it.absolutePath }}")
    }

    /**
     * The body of the `suspend fun $name` overload whose parameter list mentions [parameter].
     *
     * `deleteAttachmentsForNote` is declared twice — the dead tokenless overload first, the
     * token-aware one second — so the declaration is chosen by its parameters, not by position.
     */
    private fun methodBodyCarrying(source: String, name: String, parameter: String): String {
        val declaration = Regex("""suspend fun $name\(""").findAll(source)
            .firstOrNull { candidate ->
                val parametersEnd = parameterListEnd(source, candidate.range.last)
                parametersEnd >= 0 &&
                    source.substring(candidate.range.last, parametersEnd).contains(parameter)
            } ?: return ""
        val parametersEnd = parameterListEnd(source, declaration.range.last)
        val open = source.indexOf('{', parametersEnd)
        return if (open < 0) "" else source.substring(open, matchingBrace(source, open))
    }

    private fun matchingBrace(source: String, open: Int): Int {
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
        }
        return source.length
    }

    private fun parameterListEnd(source: String, fromIndex: Int): Int {
        var depth = 0
        for (index in fromIndex until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private companion object {
        const val UID_A = "user-a"
        const val GUEST_OWNER = "guest"
        const val TOKEN_A = "token-a"
        const val NOTE_ID = 42L
        const val ATT_A = "att-a"
        const val NOW = 2_000_000_000_000L

        val TIMEOUT = 60.seconds
    }
}
