package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentBlobUploadResult
import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.remote.OperationRemoteIdentity
import com.aus.notelikeus.data.remote.SupabaseAttachmentMetadata
import com.aus.notelikeus.data.remote.SupabaseRpcClient
import com.aus.notelikeus.data.sync.FakeNoteDao
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.data.sync.testIdentity
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.domain.repository.LocalCommitToken
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Whose blob account a token-aware deletion deletes in — R18.
 *
 * Both record-delete families capture one immutable `AttachmentRemoteContext` per logical operation
 * (R14/R16) and fence their mutations with one-shot grants (R16/R16.1). Neither proved that the
 * captured context *belongs to the operation that supplied the `LocalCommitToken`*: the context is a
 * live-session snapshot, so a session that changed during the capture — before any isolation had
 * begun, generation still N and the quarantine clear — could hand an account-A deletion a context
 * belonging to B. With colliding note/attachment ids that is a cross-account object deletion.
 *
 * The upload path has had this rule since R14 (`requireContextBelongsToOperation`, judged *after* the
 * generation decision), and these lanes apply the same helper, in the same order, to both delete
 * families: a boundary that lands behind the capture is staleness, while a wrong context under a
 * current generation is an invariant failure that starts nothing.
 *
 * Deterministic `CompletableDeferred` barriers only, no sleeps. The blob double records the **context
 * of every deletion**, which is the earliest meaningful observable: a refusal must leave that list
 * empty rather than merely produce a different final state.
 */
class AttachmentDeleteRemoteIdentityFenceTest {

    private var currentUid: String = UID
    private var noteDao = FakeNoteDao()
    private lateinit var blob: RecordingBlobTransport
    private lateinit var rpc: RecordingMetadataRpc
    private lateinit var staging: MemoryStagingStore

    private fun setUpFixture() {
        currentUid = UID
        noteDao = FakeNoteDao()
        blob = RecordingBlobTransport()
        rpc = RecordingMetadataRpc()
        staging = MemoryStagingStore()
    }

    // ---- ADRIF-1 / ADRIF-2: the window before isolation begins ----

    @Test
    fun `ADRIF-1 a current generation with another account's context deletes nothing`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            // No boundary anywhere: the token's generation is still current and the quarantine is clear.
            // Only the context is from another account — the shape a live-session change during the
            // capture produces when isolation has not started yet.
            blob.context = AttachmentRemoteContext(ownerId = UID_B, accessToken = TOKEN_B)
            val token = LocalCommitGate.capture(UID)

            val outcome = runCatching {
                service().deleteAttachmentsForNote(NOTE_ID, attachments(ATT_A), token)
            }

            // Asserted first: the contexts the transport was actually asked to delete under are the
            // earliest observable, and a refusal has to leave that list empty.
            assertEquals(
                emptyList(),
                blob.deletes.map { it.toString() },
                "a deletion ran against an account the operation did not start under",
            )
            assertTrue(
                outcome.isFailure,
                "an incoherent context under a current generation was accepted: ${outcome.getOrNull()}",
            )
            assertIs<IllegalStateException>(
                outcome.exceptionOrNull(),
                "the incoherence was reported as something other than an invariant failure: " +
                    "${outcome.exceptionOrNull()}",
            )
        }

    @Test
    fun `ADRIF-2 a sweep whose blob context belongs to another account deletes nothing`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            rpc.pending += NOTE_ID.toString() to ATT_A
            blob.context = AttachmentRemoteContext(ownerId = UID_B, accessToken = TOKEN_B)
            val token = LocalCommitGate.capture(UID)

            val outcome = runCatching {
                service().sweepPendingDeletedAttachments(token, testIdentity(UID))
            }

            assertEquals(
                emptyList(),
                blob.deletes.map { it.toString() },
                "a stale-account deletion reached the wire",
            )
            assertEquals(emptyList(), rpc.purges, "a purge ran before the sweep judged its own context")
            assertTrue(outcome.isFailure, "an incoherent sweep context was accepted: ${outcome.getOrNull()}")
            assertIs<IllegalStateException>(
                outcome.exceptionOrNull(),
                "the sweep's incoherence was reported as something else: ${outcome.exceptionOrNull()}",
            )
        }

    // ---- ADRIF-3 / ADRIF-4: a boundary behind the capture is staleness, not incoherence ----

    @Test
    fun `ADRIF-3 a different-account boundary during the capture is reported stale`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            blob.parkCapture(number = 1)
            val token = LocalCommitGate.capture(UID)

            val deletion = async(Dispatchers.IO) {
                runCatching { service().deleteAttachmentsForNote(NOTE_ID, attachments(ATT_A), token) }
            }
            blob.awaitCaptureParked()
            withContext(Dispatchers.IO) {
                LocalCommitGate.isolate {
                    currentUid = UID_B
                    blob.context = AttachmentRemoteContext(ownerId = UID_B, accessToken = TOKEN_B)
                }
            }
            blob.releaseCapture()
            val outcome = deletion.await()

            assertTrue(outcome.isSuccess, "the refusal surfaced as a failure: ${outcome.exceptionOrNull()}")
            assertIs<LocalCommitResult.StaleGeneration>(
                outcome.getOrNull(),
                "a boundary behind the capture was reported as something other than staleness",
            )
            assertEquals(emptyList(), blob.deletes, "a deletion ran for a replaced dataset")
        }

    @Test
    fun `ADRIF-3b a sweep boundary during the capture is reported stale`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        rpc.pending += NOTE_ID.toString() to ATT_A
        blob.parkCapture(number = 1)
        val token = LocalCommitGate.capture(UID)

        val sweep = async(Dispatchers.IO) {
            runCatching { service().sweepPendingDeletedAttachments(token, testIdentity(UID)) }
        }
        blob.awaitCaptureParked()
        withContext(Dispatchers.IO) {
            LocalCommitGate.isolate {
                currentUid = UID_B
                blob.context = AttachmentRemoteContext(ownerId = UID_B, accessToken = TOKEN_B)
            }
        }
        blob.releaseCapture()
        val outcome = sweep.await()

        assertTrue(outcome.isSuccess, "the refusal surfaced as a failure: ${outcome.exceptionOrNull()}")
        assertIs<LocalCommitResult.StaleGeneration>(
            outcome.getOrNull(),
            "the sweep reported a boundary behind its capture as something other than staleness",
        )
        assertEquals(emptyList(), blob.deletes, "a stale-account deletion reached the wire")
        assertEquals(emptyList(), rpc.purges, "a stale sweep still purged metadata")
    }

    @Test
    fun `ADRIF-4 a same-uid generation replacement during the capture is reported stale`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            blob.parkCapture(number = 1)
            val token = LocalCommitGate.capture(UID)

            val deletion = async(Dispatchers.IO) {
                runCatching { service().deleteAttachmentsForNote(NOTE_ID, attachments(ATT_A), token) }
            }
            blob.awaitCaptureParked()
            withContext(Dispatchers.IO) { LocalCommitGate.isolate { } }
            blob.releaseCapture()
            val outcome = deletion.await()

            assertEquals(
                UID,
                currentUid,
                "precondition: the uid is identical on both sides of the boundary, so uid equality " +
                    "cannot rescue this operation",
            )
            assertTrue(outcome.isSuccess, "the refusal surfaced as a failure: ${outcome.exceptionOrNull()}")
            assertIs<LocalCommitResult.StaleGeneration>(outcome.getOrNull())
            assertEquals(emptyList(), blob.deletes, "a deletion ran for the replaced generation")
        }

    @Test
    fun `ADRIF-4b a sweep with a same-uid replacement during the capture is reported stale`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            rpc.pending += NOTE_ID.toString() to ATT_A
            blob.parkCapture(number = 1)
            val token = LocalCommitGate.capture(UID)

            val sweep = async(Dispatchers.IO) {
                runCatching { service().sweepPendingDeletedAttachments(token, testIdentity(UID)) }
            }
            blob.awaitCaptureParked()
            withContext(Dispatchers.IO) { LocalCommitGate.isolate { } }
            blob.releaseCapture()
            val outcome = sweep.await()

            assertEquals(UID, currentUid, "precondition: the uid did not change")
            assertTrue(outcome.isSuccess, "the refusal surfaced as a failure: ${outcome.exceptionOrNull()}")
            assertIs<LocalCommitResult.StaleGeneration>(outcome.getOrNull())
            assertEquals(emptyList(), blob.deletes, "a deletion ran for the replaced generation")
        }

    // ---- ADRIF-5 / ADRIF-6 / ADRIF-7: the accepted paths are unchanged ----

    @Test
    fun `ADRIF-5 a valid operation deletes under its own context`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val token = LocalCommitGate.capture(UID)

        val result = service().deleteAttachmentsForNote(NOTE_ID, attachments(ATT_A), token)

        assertEquals(LocalCommitResult.Applied(Unit), result, "a valid deletion was refused")
        assertEquals(
            listOf(NOTE_ID.toString() to ATT_A),
            blob.deletes.map { it.noteId to it.attachmentId },
            "the deletion did not reach the wire",
        )
        assertTrue(
            blob.deletes.all { it.ownerId == UID && it.accessToken == TOKEN },
            "the deletion used a context other than the operation's own: ${blob.deletes}",
        )
    }

    @Test
    fun `ADRIF-6 a multi-object deletion captures one context and reuses it`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val token = LocalCommitGate.capture(UID)

        val result = service().deleteAttachmentsForNote(NOTE_ID, attachments(ATT_A, ATT_B), token)

        assertEquals(LocalCommitResult.Applied(Unit), result, "a valid multi-object deletion was refused")
        assertEquals(1, blob.captures.size, "the logical operation captured a context per object")
        assertEquals(2, blob.deletes.size, "not every object was deleted")
        assertEquals(
            1,
            blob.deletes.map { it.ownerId to it.accessToken }.toSet().size,
            "the objects were deleted under different contexts: ${blob.deletes}",
        )
    }

    @Test
    fun `ADRIF-7 a sweep with several records uses one coherent blob context`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            rpc.pending += NOTE_ID.toString() to ATT_A
            rpc.pending += NOTE_ID.toString() to ATT_B
            val token = LocalCommitGate.capture(UID)

            val result = service().sweepPendingDeletedAttachments(token, testIdentity(UID))

            assertEquals(LocalCommitResult.Applied(Unit), result, "a valid sweep was refused")
            assertEquals(1, blob.captures.size, "the sweep captured a blob context per record")
            assertEquals(2, blob.deletes.size, "not every pending record was deleted")
            assertTrue(
                blob.deletes.all { it.ownerId == UID },
                "a record was deleted under another account's context: ${blob.deletes}",
            )
            assertEquals(2, rpc.purges.size, "not every purged row followed its deletion")
        }

    // ---- ADRIF-8 / ADRIF-10: F-10 issuance, and no gate across the network ----

    @Test
    fun `ADRIF-8 a deletion granted before isolation still uses its own context`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            blob.parkDelete(number = 1)
            val token = LocalCommitGate.capture(UID)

            val deletion = async(Dispatchers.IO) {
                runCatching { service().deleteAttachmentsForNote(NOTE_ID, attachments(ATT_A), token) }
            }
            blob.awaitDeleteParked()

            assertTrue(
                blob.deletes.isNotEmpty(),
                "precondition: the deletion was already issued",
            )
            assertTrue(
                blob.deletes.all { it.ownerId == UID },
                "the issued deletion did not carry the operation's context",
            )
            LocalCommitGate.isolate { }
            currentUid = UID_B
            blob.releaseDelete()
            val outcome = deletion.await()

            assertTrue(outcome.isSuccess, "the refusal surfaced as a failure: ${outcome.exceptionOrNull()}")
            // Accepted shape for this family: unlike the sweep, the token-aware deletion has no
            // post-delete generation decision — its caller applies the outcome under its own commit —
            // so an already-issued deletion that completes across the boundary leaves `Applied`. What
            // R18 owns is that it used the operation's own context, and that nothing was re-issued.
            assertEquals(LocalCommitResult.Applied(Unit), outcome.getOrNull(), "the issued deletion changed shape")
            assertEquals(
                listOf(UID),
                blob.deletes.map { it.ownerId },
                "the already-issued deletion was re-issued, or re-issued under the live session's account",
            )
        }

    @Test
    fun `ADRIF-10 no credential or blob IO happens under the gate`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        blob.parkDelete(number = 1)
        val token = LocalCommitGate.capture(UID)

        val deletion = async(Dispatchers.IO) {
            runCatching { service().deleteAttachmentsForNote(NOTE_ID, attachments(ATT_A), token) }
        }
        blob.awaitDeleteParked()

        assertTrue(
            LocalCommitGate.mutex.isLocked.not(),
            "the blob deletion is in flight with LocalCommitGate held",
        )
        var isolated = false
        withContext(Dispatchers.IO) { LocalCommitGate.isolate { isolated = true } }
        assertTrue(isolated, "account isolation could not complete while the deletion was outstanding")
        blob.releaseDelete()
        deletion.await()
    }

    // ---- ADRIF-9 / ADRIF-11 / ADRIF-12: structural closure ----

    @Test
    fun `ADRIF-9 owner coherence is judged before any deletion grant`() {
        val source = serviceSource()
        for (member in listOf("deleteAttachmentsForNote", "sweepPendingDeletedAttachments")) {
            val body = methodBodyCarrying(source, member, "commitToken")
            assertTrue(body.isNotEmpty(), "precondition: $member was located")
            val coherence = body.indexOf("requireContextBelongsToOperation(")
            val grant = body.indexOf("authorizeRemoteMutationStart(")
            assertTrue(coherence >= 0, "$member does not judge its captured context against the token")
            assertTrue(
                grant < 0 || coherence < grant,
                "$member creates a deletion grant before its context has been judged coherent",
            )
            val decision = body.indexOf("validateGeneration(commitToken)")
            val commitDecision = body.indexOf("LocalCommitGate.commit(commitToken)")
            val firstDecision = listOf(decision, commitDecision).filter { it >= 0 }.minOrNull() ?: -1
            assertTrue(
                firstDecision in 0 until coherence,
                "$member judges coherence before its authoritative generation decision",
            )
        }
    }

    @Test
    fun `ADRIF-11 the sweep's metadata identity and blob context both belong to the origin`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            rpc.pending += NOTE_ID.toString() to ATT_A
            val token = LocalCommitGate.capture(UID)

            service().sweepPendingDeletedAttachments(token, testIdentity(UID))

            // Each identity is individually coherent with the originating token, which is the single
            // source of truth: there is deliberately no third cross-context mechanism comparing them.
            assertEquals(
                listOf(UID),
                rpc.identities.map { it.ownerId }.distinct(),
                "the metadata RPCs did not run under the originating account",
            )
            assertEquals(
                listOf(UID),
                blob.deletes.map { it.ownerId }.distinct(),
                "the blob deletions did not run under the originating account",
            )
            assertEquals(listOf(UID), listOf(token.initiatingUid), "precondition: the operation's account")
        }

    @Test
    fun `ADRIF-12 the dead tokenless delete overload has no production caller`() {
        val declaration = "suspend fun deleteAttachmentsForNote(noteId: Long, attachments: List<Attachment>)"
        assertTrue(
            serviceSource().contains(declaration),
            "precondition: the legacy tokenless overload was located",
        )
        // A call is the dead overload's only if its argument list does *not* carry the originating
        // token: every production caller uses the 3-argument form.
        val tokenlessCallers = productionSources()
            .flatMap { path ->
                val text = path.readText()
                Regex("""\.deleteAttachmentsForNote\(""").findAll(text)
                    .map { match -> path.name to argumentList(text, match.range.last) }
                    .filter { (_, arguments) -> "commitToken" !in arguments }
            }
        assertEquals(
            emptyList(),
            tokenlessCallers,
            "the tokenless overload is no longer dead, and it is not part of R18's closure",
        )
    }

    // ---- fixtures ----

    private fun service() = AttachmentSyncService(
        blobTransport = blob,
        metadata = SupabaseAttachmentMetadata(rpc),
        localStorage = NoopLocalStorage(),
        noteDao = noteDao,
        staging = staging,
        ownerIdProvider = { currentUid },
        attachmentsEnabled = { true },
    )

    private fun attachments(vararg ids: String) = ids.map { id ->
        Attachment(
            id = id,
            noteId = NOTE_ID,
            storagePath = "$ATTACHMENT_R2_PREFIX owners/$UID/notes/$NOTE_ID/$id",
            type = "image",
            mimeType = "image/png",
            sizeBytes = 3,
        )
    }

    /** Records the context of every deletion, which is the earliest observable a refusal must empty. */
    private class RecordingBlobTransport : AttachmentBlobTransport {
        data class Deletion(
            val noteId: String,
            val attachmentId: String,
            val ownerId: String,
            val accessToken: String,
        )

        val captures = mutableListOf<AttachmentRemoteContext>()
        val deletes = mutableListOf<Deletion>()
        var context: AttachmentRemoteContext = AttachmentRemoteContext(ownerId = UID, accessToken = TOKEN)

        private var parkCaptureNumber: Int? = null
        private var parkDeleteNumber: Int? = null
        private val captureParked = CompletableDeferred<Unit>()
        private val captureReleased = CompletableDeferred<Unit>()
        private val deleteParked = CompletableDeferred<Unit>()
        private val deleteReleased = CompletableDeferred<Unit>()

        fun parkCapture(number: Int) {
            parkCaptureNumber = number
        }

        fun parkDelete(number: Int) {
            parkDeleteNumber = number
        }

        suspend fun awaitCaptureParked() = captureParked.await()

        fun releaseCapture() {
            captureReleased.complete(Unit)
        }

        suspend fun awaitDeleteParked() = deleteParked.await()

        fun releaseDelete() {
            deleteReleased.complete(Unit)
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
        ): AttachmentBlobUploadResult = AttachmentBlobUploadResult(
            objectKey = "owners/${context.ownerId}/notes/$noteId/$attachmentId",
            sizeBytes = bytes.size.toLong(),
            mimeType = mimeType,
        )

        override suspend fun download(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
        ): ByteArray = error("no download expected in this suite")

        override suspend fun delete(context: AttachmentRemoteContext, noteId: String, attachmentId: String) {
            deletes += Deletion(noteId, attachmentId, context.ownerId, context.accessToken)
            if (parkDeleteNumber == deletes.size) {
                deleteParked.complete(Unit)
                deleteReleased.await()
            }
        }
    }

    /** Serves pending-deleted rows and records the identity each RPC was sent under. */
    private class RecordingMetadataRpc : SupabaseRpcClient {
        val pending = mutableListOf<Pair<String, String>>()
        val purges = mutableListOf<Pair<String, String>>()
        val identities = mutableListOf<OperationRemoteIdentity>()

        override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject = JsonObject(emptyMap())

        override suspend fun callRpc(
            identity: OperationRemoteIdentity,
            functionName: String,
            body: JsonObject,
        ): JsonObject {
            identities += identity
            if (functionName == "purge_deleted_note_attachment") {
                purges += body["p_attachment_id"]?.toString()?.trim('"').orEmpty() to
                    body["p_note_id"]?.toString()?.trim('"').orEmpty()
            }
            return JsonObject(emptyMap())
        }

        override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement =
            JsonArray(emptyList())

        override suspend fun callRpcElement(
            identity: OperationRemoteIdentity,
            functionName: String,
            body: JsonObject,
        ): JsonElement {
            identities += identity
            return JsonArray(
                pending.map { (noteId, attachmentId) ->
                    buildJsonObject {
                        put("attachment_id", attachmentId)
                        put("note_id", noteId)
                        put("object_key", "owners/$UID/notes/$noteId/$attachmentId")
                    }
                },
            )
        }
    }

    private class MemoryStagingStore : AttachmentStagingStore {
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

        override suspend fun bindNote(attachmentId: String, ownerId: String, noteId: Long) {}

        override suspend fun release(attachmentId: String, ownerId: String) {}

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

    /** Every production source file, for the dead-overload reachability check. */
    private fun productionSources(): List<File> = listOf(
        File("src/commonMain/kotlin/com/aus/notelikeus"),
        File("composeApp/src/commonMain/kotlin/com/aus/notelikeus"),
        File("src/androidMain/kotlin/com/aus/notelikeus"),
        File("composeApp/src/androidMain/kotlin/com/aus/notelikeus"),
        File("src/desktopMain/kotlin/com/aus/notelikeus"),
        File("composeApp/src/desktopMain/kotlin/com/aus/notelikeus"),
    ).filter { it.isDirectory }
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }

    /**
     * The body of the `suspend fun $name` overload whose parameter list mentions [parameter].
     *
     * `deleteAttachmentsForNote` is declared twice — the dead tokenless overload first, the
     * token-aware one second — and only the latter is in R18's closure.
     */
    private fun methodBodyCarrying(source: String, name: String, parameter: String): String {
        val matching = Regex("""suspend fun $name\(""").findAll(source).firstOrNull { declaration ->
            val end = parameterListEndFrom(source, declaration.range.last)
            end >= 0 && source.substring(declaration.range.last, end).contains(parameter)
        } ?: return ""
        return blockFrom(source, parameterListEndFrom(source, matching.range.last))
    }

    /** The text of one call's argument list, from its opening paren to its match. */
    private fun argumentList(source: String, fromIndex: Int): String {
        val end = parameterListEndFrom(source, fromIndex)
        return if (end < 0) "" else source.substring(fromIndex, end + 1)
    }

    private fun parameterListEndFrom(source: String, fromIndex: Int): Int {
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

    private fun blockFrom(source: String, parameterListEnd: Int): String {
        if (parameterListEnd < 0) return ""
        val open = source.indexOf('{', parameterListEnd)
        if (open < 0) return ""
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        return source.substring(open)
    }

    private companion object {
        const val UID = "same-user"
        const val UID_B = "other-user"
        const val TOKEN = "token-a"
        const val TOKEN_B = "token-b"

        const val NOTE_ID = 42L
        const val ATT_A = "att-a"
        const val ATT_B = "att-b"

        val TIMEOUT = 60.seconds
    }
}
