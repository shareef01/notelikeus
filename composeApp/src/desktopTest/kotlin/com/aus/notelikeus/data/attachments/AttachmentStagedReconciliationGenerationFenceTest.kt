package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.mapper.toNote
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentBlobUploadResult
import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.sync.CloudNoteRecord
import com.aus.notelikeus.data.sync.FakeCloudNoteTransport
import com.aus.notelikeus.data.sync.FakeLabelDao
import com.aus.notelikeus.data.sync.FakeNoteDao
import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.testRemoteIdentityProvider
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.domain.repository.LocalCommitTokenProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Staged reconciliation's **generation** authority — R14, closing F-3.
 *
 * `downloadAllNotes` resumes uploads for bytes staged before a restart, and rewrites the local note
 * row with the paths the upload produced. Both halves ran on the download's authority with nothing
 * carrying that authority into the helper: the reconciliation re-read the note table, re-snapshotted
 * its remote identity and wrote the row back, so a download that had been replaced mid-run could
 * still upload an old generation's bytes and then stamp the resulting paths over whatever note the
 * *replacement* generation had put at the same id.
 *
 * Note ids are per-device autoincrements, so "the same id" is the normal case across a boundary, not
 * the pathological one — which is why the state assertions below are the substance of the suite
 * rather than a formality. Same UID throughout, deliberately: a uid comparison cannot see this.
 *
 * The chain being pinned, end to end:
 *
 * ```
 * downloadAllNotes' token  ->  reconcileStagedAttachments(token)  ->  per-upload grant
 *                              local row write under that token
 *                              explicit StaleGeneration out to the caller's tail
 * ```
 *
 * ASRGF-1/ASRGF-2 is the recorded pre-fix red and drives the engine, so the same test body was the
 * red and is the green. No sleeps: every window is a `CompletableDeferred` park and each boundary is
 * landed from a real second thread.
 */
class AttachmentStagedReconciliationGenerationFenceTest {

    private var currentUid: String = UID
    private var noteDao = FakeNoteDao()
    private var stateStore = FakeNoteSyncStateStore()
    private lateinit var staging: MemoryStagingStore
    private lateinit var blob: RecordingBlobTransport
    private lateinit var cloud: FakeCloudNoteTransport

    private fun setUpFixture() {
        currentUid = UID
        noteDao = FakeNoteDao()
        stateStore = FakeNoteSyncStateStore().apply {
            setLastMergedUserId(UID)
            setKnownCloudIds(setOf(NOTE_ID))
        }
        staging = MemoryStagingStore()
        blob = RecordingBlobTransport()
        cloud = FakeCloudNoteTransport()
    }

    // ---- ASRGF-1 / ASRGF-2 / ASRGF-5 / RED-F-3: through the download ----

    /**
     * Pre-fix behaviour, recorded before the fix: the stale reconciliation uploaded its bytes and
     * wrote the pre-boundary note row straight over the one the replacement generation had just
     * created at the same id —
     *
     * ```
     * expected the replacement generation's attachment state
     * but was  `att-1` -> `r2:owners/same-user/notes/42/att-1`
     * ```
     *
     * The three assertions are deliberately separate. The row's state is what the user would lose;
     * the upload count is what makes the failure legible; the sync-metadata write is the download
     * *tail*, which must not run for a dataset reconciliation has just proved gone. None is vacuous —
     * all three change.
     */
    @Test
    fun `ASRGF-1 and ASRGF-2 a replaced generation cannot upload or rewrite the replacement row`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            seedCloudRecordMatchingTheLocalNote()
            seedLocalNoteWithStagedAttachment()
            blob.parkCapture(number = 1)

            val engine = engine()
            val download = async(Dispatchers.IO) { engine.downloadAllNotes() }
            blob.awaitCaptureParked()

            // Generation N's reconciliation is parked in ordinary code, past the point where it read
            // the note table. The boundary replaces the dataset *and* the row at the same id.
            lateinit var replacementState: String
            withContext(Dispatchers.IO) {
                LocalCommitGate.isolate {
                    stateStore.clear()
                    stateStore.setLastMergedUserId(UID)
                    noteDao.notes.clear()
                    noteDao.notes[NOTE_ID] = replacementNote().toNoteEntity()
                    replacementState = noteDao.notes.getValue(NOTE_ID).attachmentsJson.orEmpty()
                }
            }
            assertTrue(
                replacementState.isNotEmpty(),
                "precondition: the replacement row carries its own attachment state",
            )

            blob.releaseCapture()
            val result = download.await()

            assertTrue(result.isSuccess, "the refusal surfaced as a failure: ${result.exceptionOrNull()}")
            assertEquals(
                replacementState,
                noteDao.notes[NOTE_ID]?.attachmentsJson.orEmpty(),
                "the replaced generation's reconciliation rewrote the replacement row's attachments",
            )
            assertEquals(
                emptyList(),
                blob.uploads.map { it.attachmentId },
                "stale staged reconciliation started a blob upload",
            )
            assertEquals(
                emptyList(),
                cloud.syncMetaCalls,
                "ASRGF-5: a stale reconciliation let the download carry on to its tail",
            )
        }

    // ---- ASRGF-3: the upload is refused at the service ----

    /**
     * The same refusal one layer down, so the grant rather than the caller is what is being
     * exercised: reconciliation is entered with a live token, the generation is replaced while its
     * identity snapshot is being taken, and nothing reaches the wire.
     */
    @Test
    fun `ASRGF-3 a boundary before the remote upload starts no upload`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            seedLocalNoteWithStagedAttachment()
            val token = LocalCommitGate.capture(UID)
            blob.parkCapture(number = 1)

            val reconciliation = async(Dispatchers.IO) { service().reconcileStagedAttachments(token) }
            blob.awaitCaptureParked()
            withContext(Dispatchers.IO) { LocalCommitGate.isolate { } }
            blob.releaseCapture()
            val result = reconciliation.await()

            assertEquals(UID, currentUid, "precondition: the uid is identical across the boundary")
            assertIs<LocalCommitResult.StaleGeneration>(
                result,
                "uid equality rescued a reconciliation whose generation had been replaced",
            )
            assertEquals(emptyList(), blob.uploads, "a refused reconciliation still uploaded")
        }

    // ---- ASRGF-4: an upload already on the wire, and its refused row write ----

    /**
     * The already-issued upload stands — the application-level issuance model is unchanged — and the
     * *row write* that would have recorded it does not happen, because it is a separate, later,
     * account-owned local mutation that takes its own decision.
     */
    @Test
    fun `ASRGF-4 an upload already issued stands and its local row write is refused`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            seedLocalNoteWithStagedAttachment()
            val token = LocalCommitGate.capture(UID)
            blob.parkUpload(number = 1)

            val reconciliation = async(Dispatchers.IO) { service().reconcileStagedAttachments(token) }
            blob.awaitUploadParked()

            lateinit var replacementState: String
            withContext(Dispatchers.IO) {
                LocalCommitGate.isolate {
                    noteDao.notes.clear()
                    noteDao.notes[NOTE_ID] = replacementNote().toNoteEntity()
                    replacementState = noteDao.notes.getValue(NOTE_ID).attachmentsJson.orEmpty()
                }
            }
            blob.releaseUpload()
            val result = reconciliation.await()

            assertEquals(
                listOf(ATTACHMENT_ID),
                blob.uploads.map { it.attachmentId },
                "the already-authorized upload should have been issued exactly once",
            )
            assertIs<LocalCommitResult.StaleGeneration>(
                result,
                "a reconciliation whose dataset vanished mid-upload reported success",
            )
            assertEquals(
                replacementState,
                noteDao.notes[NOTE_ID]?.attachmentsJson.orEmpty(),
                "the refused row write still mutated the replacement note",
            )
        }

    // ---- ASRGF-6: the valid case is unchanged ----

    @Test
    fun `ASRGF-6 a valid reconciliation still uploads and records the resumed count`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            seedLocalNoteWithStagedAttachment()

            val result = service().reconcileStagedAttachments(LocalCommitGate.capture(UID))

            assertEquals(LocalCommitResult.Applied(1), result, "a valid reconciliation did not resume")
            assertEquals(
                listOf(ATTACHMENT_ID),
                blob.uploads.map { it.attachmentId },
                "the staged attachment was not uploaded exactly once",
            )
            val durable = assertNotNull(noteDao.getNoteById(NOTE_ID)).note.toNote()
            assertEquals(
                "r2:owners/$UID/notes/$NOTE_ID/$ATTACHMENT_ID",
                durable.attachments.single().storagePath,
                "the uploaded path did not reach the note row",
            )
        }

    // ---- ASRGF-7: a post-suspension no-op cannot be reported as success ----

    /**
     * The listing suspends, so its *outcome* cannot authorize the return that follows it — including
     * the outcome that looks harmless. Nothing was staged, nothing was uploaded and nothing needed
     * doing, and the answer is still a refusal rather than `Applied(0)`, because this run's dataset
     * is gone and the caller's remaining tail is new work for a dataset that no longer exists.
     */
    @Test
    fun `ASRGF-7 an empty post-suspension reconciliation is not reported as applied`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            val stale = LocalCommitGate.capture(UID)
            LocalCommitGate.isolate { }

            val result = service().reconcileStagedAttachments(stale)

            assertIs<LocalCommitResult.StaleGeneration>(
                result,
                "a no-work reconciliation reported success for a replaced dataset",
            )
            assertEquals(emptyList(), blob.uploads, "a no-work reconciliation uploaded anyway")
        }

    // ---- ASRGF-8: failure semantics are unchanged ----

    /**
     * An upload that fails is not a boundary. Historically the reconciliation logged it and carried
     * on, leaving the staged bytes for the next attempt; it still does, and the caller still gets a
     * success rather than a refusal it would act on.
     */
    @Test
    fun `ASRGF-8 an upload failure stays a caught failure and resumes nothing`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            seedLocalNoteWithStagedAttachment()
            blob.failure = WorkerDown("worker down")

            val result = service().reconcileStagedAttachments(LocalCommitGate.capture(UID))

            assertEquals(LocalCommitResult.Applied(0), result, "a failed upload was reported as stale")
            assertEquals(
                listOf(ATTACHMENT_ID),
                blob.uploads.map { it.attachmentId },
                "the failed upload should have been attempted exactly once",
            )
            assertEquals(
                pendingStoragePath(ATTACHMENT_ID),
                assertNotNull(noteDao.getNoteById(NOTE_ID)).note.toNote().attachments.single().storagePath,
                "a failed upload changed the local note",
            )
        }

    // ---- ASRGF-9: one originating token, and it is not recaptured ----

    /**
     * `reconcileStagedAttachments` must take the download's token and mint none of its own.
     *
     * Driven behaviourally by the cases above, and pinned structurally here for the paths a
     * behavioural test cannot reach: the method has exactly one `LocalCommitToken` parameter, its
     * uploads are authorized against that parameter, and no fresh capture appears anywhere in it.
     */
    @Test
    fun `ASRGF-9 reconciliation threads the caller's token and recaptures nothing`() {
        val source = attachmentServiceSource()
        val signature = methodSignature(source, "reconcileStagedAttachments")
        val body = methodBody(source, "reconcileStagedAttachments")

        assertTrue(signature.isNotBlank(), "precondition: the reconciliation method was not found")
        assertTrue(
            signature.contains("commitToken: LocalCommitToken,"),
            "reconciliation no longer takes the caller's LocalCommitToken",
        )
        assertFalse(
            signature.contains("commitToken: LocalCommitToken?"),
            "reconciliation's token became optional, so a caller could leave the fence out",
        )
        assertFalse(
            body.contains("LocalCommitGate.capture("),
            "reconciliation minted a generation of its own",
        )
        assertFalse(
            body.contains("LocalCommitTokenProvider"),
            "reconciliation pulled a token from the provider seam",
        )
        // Both account-owned local mutations carry the originating token: the per-note row write,
        // and the trailing release of bytes no note references. They live in two members, so each is
        // asserted where it is.
        val rowWrite = methodBody(source, "resumeStagedUploadsFor")
        assertTrue(rowWrite.isNotBlank(), "precondition: the per-note resume helper was not found")
        assertFalse(
            rowWrite.contains("LocalCommitGate.capture("),
            "the per-note resume helper minted a generation of its own",
        )
        assertEquals(
            1,
            Regex("""LocalCommitGate\.commit\(commitToken\)""").findAll(rowWrite).count(),
            "the resumed row write is no longer fenced on the originating token",
        )
        assertEquals(
            1,
            Regex("""LocalCommitGate\.commit\(commitToken\)""").findAll(body).count(),
            "the staged-byte release is no longer fenced on the originating token",
        )
    }

    // ---- ASRGF-10: no later entry is processed once stale ----

    /**
     * Two notes, each with staged bytes. The boundary lands behind the *first* note's upload, which
     * was already authorized, so that upload stands — and the second note is never reached, neither
     * its upload nor its row.
     */
    @Test
    fun `ASRGF-10 no later note is processed after the generation is found gone`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            seedLocalNoteWithStagedAttachment()
            seedLocalNote(OTHER_ID, otherAttachment())
            val token = LocalCommitGate.capture(UID)
            blob.parkUpload(number = 1)

            val reconciliation = async(Dispatchers.IO) { service().reconcileStagedAttachments(token) }
            blob.awaitUploadParked()
            withContext(Dispatchers.IO) { LocalCommitGate.isolate { } }
            blob.releaseUpload()
            val result = reconciliation.await()

            assertIs<LocalCommitResult.StaleGeneration>(result, "the run reported success after the boundary")
            assertEquals(
                listOf(ATTACHMENT_ID),
                blob.uploads.map { it.attachmentId },
                "a later note's attachment was uploaded after the boundary",
            )
            assertEquals(
                pendingStoragePath(OTHER_ATTACHMENT_ID),
                assertNotNull(noteDao.getNoteById(OTHER_ID)).note.toNote().attachments.single().storagePath,
                "a later note's row was rewritten after the boundary",
            )
        }

    // ---- ASRGF-11 / R14.1: the boundary lands inside the context capture ----

    /**
     * The same boundary, one layer up: reconciliation is reached from `downloadAllNotes`, the
     * context capture suspends, and the session moves to another account while it is outstanding.
     *
     * The discriminator is **how many notes get processed**. The reconciliation's own upload attempt
     * wraps `syncNoteAttachments` in a `runCatching` whose whole purpose is to keep a *transport*
     * failure from ending the run, so a context-coherence error raised before the generation decision
     * is swallowed there — and the loop then hands the second note to the same extinct generation
     * before anything notices. One capture proves the boundary stopped the run where it was found.
     */
    @Test
    fun `ASRGF-11 a boundary inside the context capture stops the tail and processes no later note`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            // Both notes are already up to date in the cloud, so the download's push-back is empty and
            // reconciliation is the first thing in the run to capture a remote context.
            seedCloudRecordMatchingTheLocalNote(NOTE_ID)
            seedCloudRecordMatchingTheLocalNote(OTHER_ID)
            seedLocalNoteWithStagedAttachment()
            seedLocalNote(OTHER_ID, otherAttachment())
            blob.parkCapture(number = 1)

            val engine = engine()
            val download = async(Dispatchers.IO) { engine.downloadAllNotes() }
            blob.awaitCaptureParked()

            withContext(Dispatchers.IO) {
                LocalCommitGate.isolate {
                    currentUid = UID_B
                    blob.context = AttachmentRemoteContext(ownerId = UID_B, accessToken = TOKEN_B)
                    stateStore.clear()
                }
            }
            blob.releaseCapture()
            val result = download.await()

            assertTrue(result.isSuccess, "the refusal surfaced as a failure: ${result.exceptionOrNull()}")
            assertEquals(
                1,
                blob.captures.size,
                "the run kept going after the boundary and handed a later note to an extinct generation",
            )
            assertEquals(emptyList(), blob.uploads.map { it.attachmentId }, "a stale context reached the wire")
            assertEquals(emptyList(), cloud.syncMetaCalls, "writeSyncMeta ran after a stale reconciliation")
            assertEquals(
                emptySet<Long>(),
                stateStore.knownCloudIds(),
                "the known-cloud-id set was updated after a stale reconciliation",
            )
            assertEquals(
                null,
                stateStore.lastMergedUserId(),
                "the merged-user marker was written after a stale reconciliation",
            )
            assertEquals(
                emptySet<Long>(),
                stateStore.pendingAttachmentGcIds(),
                "attachment GC was scheduled after a stale reconciliation",
            )
        }

    // ---- fixtures ----

    private fun replacementNote() = Note(
        id = NOTE_ID,
        title = "replacement note",
        content = "replacement body",
        timestamp = 9_000L,
        color = 4,
        serverUpdatedAt = 9_000L,
        attachments = listOf(
            Attachment(
                id = "replacement-att",
                noteId = NOTE_ID,
                storagePath = "r2:replacement/att-9",
                type = "image",
                mimeType = "image/png",
                sizeBytes = 7,
            ),
        ),
    )

    /**
     * A cloud record that says the local note is already up to date.
     *
     * Without this the download would push the local note back to the cloud first, which would sync
     * the pending attachment *before* reconciliation ever ran — the run would then be a test of the
     * upload path, not of reconciliation. Matching every field the merge compares (including a
     * confirmed `serverUpdatedAt` on both sides, which makes the cloud win the tie) leaves the
     * push-back empty and the reconciliation the first attachment work in the download.
     */
    private fun seedCloudRecordMatchingTheLocalNote(noteId: Long = NOTE_ID) {
        cloud.notes[noteId] = CloudNoteRecord(
            noteId = noteId,
            serverUpdatedAt = 1_000L,
            clientTimestamp = 1_000L,
            title = "local note",
            content = "local body",
            timestamp = 1_000L,
            color = 0,
            isPinned = false,
            isArchived = false,
            isTrashed = false,
            position = 0,
            reminderTimestamp = null,
            labels = emptyList(),
            checklistItems = emptyList(),
            revision = 1L,
        )
    }

    private fun seedLocalNoteWithStagedAttachment() {
        seedLocalNote(NOTE_ID, pendingAttachment(ATTACHMENT_ID))
    }

    private fun otherAttachment() = pendingAttachment(OTHER_ATTACHMENT_ID)

    private fun seedLocalNote(noteId: Long, attachment: Attachment) {
        noteDao.notes[noteId] = Note(
            id = noteId,
            title = "local note",
            content = "local body",
            timestamp = 1_000L,
            color = 0,
            serverUpdatedAt = 1_000L,
            attachments = listOf(attachment),
        ).toNoteEntity()
        staging.seed(attachment.id, UID, noteId, byteArrayOf(1, 2, 3), "image/png")
    }

    private fun pendingAttachment(attachmentId: String) = Attachment(
        id = attachmentId,
        noteId = NOTE_ID,
        storagePath = pendingStoragePath(attachmentId),
        type = "image",
        mimeType = "image/png",
        sizeBytes = 3,
    )

    private fun service() = AttachmentSyncService(
        blobTransport = blob,
        metadata = null,
        localStorage = NoopLocalStorage(),
        noteDao = noteDao,
        staging = staging,
        ownerIdProvider = { currentUid },
        attachmentsEnabled = { true },
    )

    private fun engine(): NoteSyncEngine = NoteSyncEngine(
        transport = cloud,
        remoteIdentityProvider = testRemoteIdentityProvider { currentUid },
        noteDao = noteDao,
        labelDao = FakeLabelDao(),
        syncStateStore = stateStore,
        uidProvider = { Result.success(currentUid) },
        platform = "desktop",
        now = { NOW },
        localCommitTokenProvider = LocalCommitTokenProvider {
            LocalCommitGate.captureCoherent { currentUid }
        },
        attachmentSync = service(),
    )

    /** Reads `AttachmentSyncService.kt` from the module this test compiles in. */
    private fun attachmentServiceSource(): String {
        val candidates = listOf(
            File("src/commonMain/kotlin/com/aus/notelikeus/data/attachments/AttachmentSyncService.kt"),
            File("composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/attachments/AttachmentSyncService.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error(
                "could not read AttachmentSyncService.kt from " +
                    candidates.joinToString { it.absolutePath },
            )
        return file.readText()
    }

    /**
     * The index of the `)` that closes one suspend member's parameter list.
     *
     * Found by paren depth rather than by taking the first `)` after the name, because a parameter
     * type may itself be parenthesised — `onMissingBytes: (Note, Attachment) -> Unit` — and the naive
     * search stops inside it.
     */
    private fun parameterListEnd(source: String, name: String): Int {
        val declaration = Regex("""suspend fun $name\(""").find(source) ?: return -1
        var depth = 0
        for (index in declaration.range.last until source.length) {
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

    /** One suspend member's declaration and parameter list, up to its closing paren. */
    private fun methodSignature(source: String, name: String): String {
        val declaration = Regex("""suspend fun $name\(""").find(source) ?: return ""
        val end = parameterListEnd(source, name)
        return if (end < 0) "" else source.substring(declaration.range.first, end + 1)
    }

    /**
     * The textual body of one suspend member, from its opening brace to its matching close.
     *
     * The parameter list is skipped first, because a defaulted parameter may itself contain a brace
     * — `onMissingBytes: ... = { }` — and taking the first `{` after the name would return that
     * lambda instead of the body.
     */
    private fun methodBody(source: String, name: String): String {
        val end = parameterListEnd(source, name)
        if (end < 0) return ""
        val open = source.indexOf('{', end)
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
                createdAt = NOW,
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
            key(attachmentId, ownerId) in bytes

        override suspend fun bindNote(attachmentId: String, ownerId: String, noteId: Long) {
            meta[key(attachmentId, ownerId)]?.let { staged ->
                meta[key(attachmentId, ownerId)] = staged.copy(noteId = noteId)
            }
        }

        override suspend fun release(attachmentId: String, ownerId: String) {
            bytes.remove(key(attachmentId, ownerId))
            meta.remove(key(attachmentId, ownerId))
        }

        override suspend fun list(ownerId: String): List<StagedAttachment> =
            meta.values.filter { it.ownerId == ownerId }
    }

    private class NoopLocalStorage : AttachmentLocalStorage {
        override fun persistImageBytes(bytes: ByteArray, extension: String): String? = null
        override fun readBytes(storagePath: String): ByteArray? = null
        override fun exists(storagePath: String): Boolean? = false
        override fun deleteIfLocal(storagePath: String) = Unit
    }

    private class RecordingBlobTransport : AttachmentBlobTransport {
        data class Upload(val attachmentId: String, val ownerId: String)

        val captures = mutableListOf<AttachmentRemoteContext>()
        val uploads = mutableListOf<Upload>()
        var failure: Throwable? = null
        var context = AttachmentRemoteContext(ownerId = UID, accessToken = TOKEN)

        private var parkCaptureNumber: Int? = null
        private var parkUploadNumber: Int? = null
        private val captureParked = CompletableDeferred<Unit>()
        private val captureReleased = CompletableDeferred<Unit>()
        private val uploadParked = CompletableDeferred<Unit>()
        private val uploadReleased = CompletableDeferred<Unit>()

        fun parkCapture(number: Int) {
            parkCaptureNumber = number
        }

        fun parkUpload(number: Int) {
            parkUploadNumber = number
        }

        suspend fun awaitCaptureParked() = captureParked.await()

        fun releaseCapture() {
            captureReleased.complete(Unit)
        }

        suspend fun awaitUploadParked() = uploadParked.await()

        fun releaseUpload() {
            uploadReleased.complete(Unit)
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
            if (parkUploadNumber == uploads.size) {
                uploadParked.complete(Unit)
                uploadReleased.await()
            }
            failure?.let { throw it }
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

        override suspend fun delete(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
        ) = error("no delete expected in this suite")
    }

    private class WorkerDown(message: String) : RuntimeException(message)

    private companion object {
        const val UID = "same-user"
        const val UID_B = "account-b"
        const val TOKEN = "token-a"
        const val TOKEN_B = "token-b"
        const val NOTE_ID = 42L
        const val OTHER_ID = 43L
        const val ATTACHMENT_ID = "att-1"
        const val OTHER_ATTACHMENT_ID = "att-2"
        const val NOW = 1_000_000L

        val TIMEOUT = 60.seconds
    }
}
