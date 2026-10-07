package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.attachments.AttachmentLocalStorage
import com.aus.notelikeus.data.attachments.AttachmentSyncService
import com.aus.notelikeus.data.attachments.FileAttachmentStagingStore
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentBlobUploadResult
import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.remote.SupabaseAttachmentMetadata
import com.aus.notelikeus.data.remote.SupabaseRpcClient
import com.aus.notelikeus.domain.repository.LocalCommitResult
import com.aus.notelikeus.domain.repository.LocalCommitToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import com.aus.notelikeus.data.remote.OperationRemoteIdentity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The pending-delete attachment sweep under the generation fence — D11 phase 3D.3H.
 *
 * The sweep was the last generation-blind corner of the attachment GC path. It took no token, so it
 * could not tell whether the run that produced the pending-deleted rows still existed: it listed the
 * server's pending rows, then deleted blob objects and purged their metadata for whatever account
 * happened to be signed in. 3D.3G could only fence its *entry*.
 *
 * This phase threads the originating [LocalCommitToken] in and takes a decision under it before each
 * row's first account-owned mutation. It is generation-**aware**, not generation-complete: the
 * listing, each blob delete and each purge can still complete across a boundary, and the empty and
 * all-rows-skipped cases return without deciding at all. Those edges are 3D.3H.1's and are listed in
 * the phase report rather than closed here — closing them would collapse two phases into one.
 *
 * Everything except ASGF-8/9 drives the service directly, because the sweep's own row decision is
 * what this phase changes; the engine-level cases only have to show the *propagation* outward.
 * Deterministic `CompletableDeferred` barriers, no sleeps. Note the fake store accessors used for
 * replacement-state assertions are already copies (`pendingAttachmentGcEntries`/`Ids`), and where a
 * snapshot is taken it is forced with `.toMap()`/`.toList()`.
 */
class NoteSyncEngineAttachmentSweepGenerationFenceTest {

    private lateinit var tempDir: File
    private lateinit var staging: FileAttachmentStagingStore

    private var currentUid: String = UID
    private var noteDao = FakeNoteDao()
    private var labelDao = FakeLabelDao()
    private var stateStore = FakeNoteSyncStateStore()

    @BeforeTest
    fun setUpDir() {
        tempDir = Files.createTempDirectory("notelikeus-sweep-fence-test").toFile()
        staging = FileAttachmentStagingStore(
            root = File(tempDir, "staging").absolutePath.toPath(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
    }

    @AfterTest
    fun tearDownDir() {
        tempDir.deleteRecursively()
    }

    private fun setUpFixture() {
        currentUid = UID
        noteDao = FakeNoteDao()
        labelDao = FakeLabelDao()
        stateStore = FakeNoteSyncStateStore().apply {
            currentTime = NOW
            setLastMergedUserId(UID)
        }
    }

    // ---- ASGF-1 / ASGF-2: an already-stale token blocks the row's mutations ----

    @Test
    fun `ASGF-1 an already-stale token prevents the first row's mutations`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
        val blob = RecordingBlobTransport()
        val stale = LocalCommitGate.capture(UID)

        LocalCommitGate.isolate { }

        val result = service(rpc, blob).sweepPendingDeletedAttachments(stale, testIdentity(UID))

        assertEquals(
            LocalCommitResult.StaleGeneration,
            result,
            "an already-stale sweep reported success",
        )
        assertTrue(blob.deletes.isEmpty(), "the stale sweep deleted a blob object")
        assertTrue(rpc.purges.isEmpty(), "the stale sweep purged metadata")
        assertTrue(rpc.listed, "precondition: the listing did run, so this is the row decision refusing")
    }

    @Test
    fun `ASGF-2 the same uid across a boundary still blocks the row's mutations`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
        val blob = RecordingBlobTransport()
        val uidBefore = currentUid
        val stale = LocalCommitGate.capture(UID)
        val generationBefore = LocalCommitGate.currentGeneration()

        LocalCommitGate.isolate { }

        val result = service(rpc, blob).sweepPendingDeletedAttachments(stale, testIdentity(UID))

        assertEquals(uidBefore, currentUid, "precondition: the uid never changed")
        assertTrue(
            LocalCommitGate.currentGeneration() > generationBefore,
            "precondition: the generation advanced",
        )
        assertEquals(LocalCommitResult.StaleGeneration, result, "the uid-only boundary was not observed")
        assertTrue(blob.deletes.isEmpty(), "the stale sweep deleted a blob object")
        assertTrue(rpc.purges.isEmpty(), "the stale sweep purged metadata")
    }

    // ---- ASGF-3: staleness observed on row A stops row B ----

    @Test
    fun `ASGF-3 staleness seen at row A stops row B`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A, ROW_B to ATT_B)
        val parked = ParkGate()
        val blob = RecordingBlobTransport(parkOnDelete = parked)
        val token = LocalCommitGate.capture(UID)

        val sweep = async { service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)) }
        parked.awaitParked()   // row A's decision passed and its blob delete is in flight

        LocalCommitGate.isolate { }
        parked.release()

        assertEquals(
            LocalCommitResult.StaleGeneration,
            sweep.await(),
            "the sweep completed both rows after the boundary",
        )
        assertEquals(
            listOf(ROW_A to ATT_A),
            blob.deletes.toList(),
            "row B's blob delete ran after staleness was observed at row B's decision",
        )
        // Row A's blob delete was already issued when the boundary landed, so it stands. The purge is a
        // *fresh* mutation, and 3D.3H.1 revalidates the generation before starting it — so it does not
        // go out. This assertion is the inverse of what 3D.3H pinned here, deliberately: H.1 closes the
        // post-suspension continuation that phase recorded as a known hole.
        assertTrue(
            rpc.purges.isEmpty(),
            "a fresh purge started after the boundary landed behind the blob delete",
        )
        assertTrue(rpc.purges.none { it.second == ROW_B }, "row B's metadata was purged after the boundary")
    }

    // ---- ASGF-4: the skip predicate is unchanged ----

    @Test
    fun `ASGF-4 a row skipped for an in-flight restore is untouched`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A, ROW_B to ATT_B)
        val blob = RecordingBlobTransport()

        val result = service(rpc, blob).sweepPendingDeletedAttachments(
            commitToken = LocalCommitGate.capture(UID),
            metadataIdentity = testIdentity(UID),
            skipNoteId = { it == ROW_A },
        )

        assertEquals(LocalCommitResult.Applied(Unit), result, "a valid sweep was refused")
        assertEquals(
            listOf(ROW_B to ATT_B),
            blob.deletes.toList(),
            "the restore-skipped row was swept anyway, or the row after it was not",
        )
        assertEquals(
            listOf(ATT_B to ROW_B),
            rpc.purges.toList(),
            "the skipped row's metadata was purged, or the following row's was not",
        )
    }

    // ---- ASGF-5 / ASGF-6 / ASGF-7: valid rows and failure semantics are unchanged ----

    @Test
    fun `ASGF-5 a valid sweep deletes and purges every row in order`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A, ROW_B to ATT_B)
        val blob = RecordingBlobTransport()

        val result = service(rpc, blob).sweepPendingDeletedAttachments(LocalCommitGate.capture(UID), testIdentity(UID))

        assertEquals(LocalCommitResult.Applied(Unit), result, "a valid sweep was refused")
        assertEquals(
            listOf(ROW_A to ATT_A, ROW_B to ATT_B),
            blob.deletes.toList(),
            "the sweep did not attempt both rows in listing order",
        )
        assertEquals(
            listOf(ATT_A to ROW_A, ATT_B to ROW_B),
            rpc.purges.toList(),
            "each row's metadata purge did not follow its blob delete",
        )
    }

    @Test
    fun `ASGF-6 a blob failure keeps its existing semantics`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A, ROW_B to ATT_B)
        val blob = RecordingBlobTransport(failDeleteFor = ROW_A)

        val result = service(rpc, blob).sweepPendingDeletedAttachments(LocalCommitGate.capture(UID), testIdentity(UID))

        assertEquals(
            LocalCommitResult.Applied(Unit),
            result,
            "a blob transport failure was turned into a generation refusal",
        )
        assertEquals(
            listOf(ROW_A to ATT_A, ROW_B to ATT_B),
            blob.deletes.toList(),
            "the failed row aborted the sweep instead of moving on",
        )
        // The purge for the failed row never runs — both calls share one `runCatching` — and that is
        // unchanged by this phase.
        assertEquals(
            listOf(ATT_B to ROW_B),
            rpc.purges.toList(),
            "the failed row's metadata was purged, or the following row's was skipped",
        )
    }

    @Test
    fun `ASGF-7 a purge failure keeps its existing semantics`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A, ROW_B to ATT_B).failPurgeFor(ATT_A)
        val blob = RecordingBlobTransport()

        val result = service(rpc, blob).sweepPendingDeletedAttachments(LocalCommitGate.capture(UID), testIdentity(UID))

        assertEquals(
            LocalCommitResult.Applied(Unit),
            result,
            "a metadata failure was turned into a generation refusal",
        )
        assertEquals(
            listOf(ROW_A to ATT_A, ROW_B to ATT_B),
            blob.deletes.toList(),
            "a purge failure aborted the sweep instead of being swallowed as before",
        )
        assertTrue(rpc.purges.any { it.second == ROW_B }, "the row after the purge failure was skipped")
    }

    // ---- ASGF-8 / ASGF-9: the sweep's staleness reaches the caller ----

    @Test
    fun `ASGF-8 a stale sweep propagates into retryPendingAttachmentGc`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
        val parked = ParkGate()
        val blob = RecordingBlobTransport(parkOnCaptureContext = parked)
        val engine = engine(service(rpc, blob))

        val retry = async { engine.retryPendingAttachmentGc(LocalCommitGate.capture(UID), testIdentity(UID)) }
        parked.awaitParked()   // the retry's entry fence passed; the sweep is between listing and rows

        LocalCommitGate.isolate { }
        parked.release()

        assertEquals(
            LocalCommitResult.StaleGeneration,
            retry.await(),
            "the sweep's staleness did not reach retryPendingAttachmentGc",
        )
        assertTrue(blob.deletes.isEmpty(), "the sweep deleted a blob object after the boundary")
        assertTrue(rpc.purges.isEmpty(), "the sweep purged metadata after the boundary")
    }

    @Test
    fun `ASGF-9 a stale sweep suppresses the download tail`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
        val parked = ParkGate()
        val blob = RecordingBlobTransport(parkOnCaptureContext = parked)
        val cloud = FakeCloudNoteTransport()
        val engine = engine(service(rpc, blob), cloud)

        val run = async { engine.downloadAllNotes() }
        parked.awaitParked()

        LocalCommitGate.isolate {
            stateStore.clear()
            stateStore.currentTime = NOW
            stateStore.setLastMergedUserId(UID)
        }
        parked.release()
        val result = run.await()

        assertTrue(result.isSuccess, "the boundary surfaced as a failure: ${result.exceptionOrNull()}")
        // Ordered tail-first because it is the stronger claim — but it is deliberately not the one the
        // phase red fires on: the download's downstream `setKnownCloudIds` commit refuses the stale
        // token before `writeSyncMeta` is reached, so the tail is already protected by an older fence.
        // What the sweep's red actually shows is its own unauthorised mutation going out, below.
        assertTrue(
            cloud.syncMetaCalls.isEmpty(),
            "the stale download wrote cloud sync metadata after the sweep refused",
        )
        assertTrue(blob.deletes.isEmpty(), "the download's sweep deleted a blob object after the boundary")
    }

    // ---- ASGF-10..16: the 3D.3H.1 suspension exits ----
    //
    // Each of these drives one suspension and moves the generation behind it. The controls inside
    // 10/11/16 pin the *other* half of the contract: on a current generation the same shape must stay
    // a no-op success, because this phase narrows stale handling only and must not change remote
    // failure policy.

    @Test
    fun `ASGF-10 an empty listing after a boundary is not a success`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val rpc = FakeRpcClient()                       // no rows, so the listing comes back empty
        val parked = ParkGate()
        rpc.parkOnList = parked
        val blob = RecordingBlobTransport()
        val syncService = service(rpc, blob)
        val token = LocalCommitGate.capture(UID)

        val sweep = async { syncService.sweepPendingDeletedAttachments(token, testIdentity(UID)) }
        parked.awaitParked()
        LocalCommitGate.isolate { }
        parked.release()

        assertEquals(
            LocalCommitResult.StaleGeneration,
            sweep.await(),
            "an empty listing after a boundary reported success instead of staleness",
        )
        assertEquals(
            LocalCommitResult.Applied(Unit),
            syncService.sweepPendingDeletedAttachments(LocalCommitGate.capture(UID), testIdentity(UID)),
            "an empty listing on a current generation stopped being a no-op success",
        )
        assertTrue(blob.deletes.isEmpty(), "an empty listing produced a blob delete")
    }

    @Test
    fun `ASGF-11 a failed listing after a boundary is not a success`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
        rpc.listFailure = IllegalStateException("listing failed")
        val parked = ParkGate()
        rpc.parkOnList = parked
        val blob = RecordingBlobTransport()
        val syncService = service(rpc, blob)
        val token = LocalCommitGate.capture(UID)

        // The failure is captured *inside* the coroutine: it is the sweep's outcome under test, and an
        // `async` whose body throws would be reported by the test scope in its own right.
        val sweep = async {
            runCatching { syncService.sweepPendingDeletedAttachments(token, testIdentity(UID)) }
        }
        parked.awaitParked()
        LocalCommitGate.isolate { }
        parked.release()

        // F-11/R22: the listing failure is what the caller sees — not a success, and not a fabricated
        // staleness either. "The server could not be asked" is its own outcome.
        assertTrue(
            sweep.await().exceptionOrNull() is IllegalStateException,
            "a failed listing was reported as a success instead of failing",
        )
        assertTrue(blob.deletes.isEmpty(), "a failed listing still reached a row")

        assertTrue(
            runCatching {
                syncService.sweepPendingDeletedAttachments(LocalCommitGate.capture(UID), testIdentity(UID))
            }.exceptionOrNull() is IllegalStateException,
            "a current-generation listing failure was reported as a success instead of failing",
        )
    }

    @Test
    fun `ASGF-12 a boundary during captureContext stops every row`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
        val parked = ParkGate()
        val blob = RecordingBlobTransport(parkOnCaptureContext = parked)
        val token = LocalCommitGate.capture(UID)

        val sweep = async { service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)) }
        parked.awaitParked()   // the listing is already back; the remote context is in flight
        LocalCommitGate.isolate { }
        parked.release()

        assertEquals(
            LocalCommitResult.StaleGeneration,
            sweep.await(),
            "a boundary during captureContext was not observed",
        )
        assertTrue(blob.deletes.isEmpty(), "a row ran after a boundary during captureContext")
        assertTrue(rpc.purges.isEmpty(), "a purge ran after a boundary during captureContext")
    }

    @Test
    fun `ASGF-13 a final-row blob failure after a boundary is not a success`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        // Exactly one row: no later row decision can mask a missing post-suspension check.
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
        val parked = ParkGate()
        val blob = RecordingBlobTransport(parkOnDelete = parked, failDeleteFor = ROW_A)
        val token = LocalCommitGate.capture(UID)

        val sweep = async { service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)) }
        parked.awaitParked()
        LocalCommitGate.isolate { }
        parked.release()

        assertEquals(
            LocalCommitResult.StaleGeneration,
            sweep.await(),
            "the final row's failed delete swallowed the boundary and reported success",
        )
        assertEquals(listOf(ROW_A to ATT_A), blob.deletes.toList(), "precondition: the delete was attempted")
        assertTrue(rpc.purges.isEmpty(), "the failed row's purge ran anyway")
    }

    @Test
    fun `ASGF-14 a final purge completing after a boundary is not a success`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        // Exactly one row, so the post-purge decision is the last thing before the success return.
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
        val parked = ParkGate()
        rpc.parkOnPurge = parked
        val blob = RecordingBlobTransport()
        val token = LocalCommitGate.capture(UID)

        val sweep = async { service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)) }
        parked.awaitParked()   // the blob delete is done; the purge is in flight
        LocalCommitGate.isolate { }
        parked.release()

        assertEquals(
            LocalCommitResult.StaleGeneration,
            sweep.await(),
            "a purge that completed across the boundary was still reported as success",
        )
        assertEquals(listOf(ROW_A to ATT_A), blob.deletes.toList(), "precondition: the delete happened")
        // The purge was already issued when the boundary landed, so it stands — this is
        // stale-continuation fencing, not remote-start authority.
        assertEquals(listOf(ATT_A to ROW_A), rpc.purges.toList(), "the already-issued purge should stand")
    }

    @Test
    fun `ASGF-15 a final purge failure after a boundary is not a success`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A).failPurgeFor(ATT_A)
        val parked = ParkGate()
        rpc.parkOnPurge = parked
        val blob = RecordingBlobTransport()
        val token = LocalCommitGate.capture(UID)

        val sweep = async { service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)) }
        parked.awaitParked()
        LocalCommitGate.isolate { }
        parked.release()

        assertEquals(
            LocalCommitResult.StaleGeneration,
            sweep.await(),
            "a swallowed purge failure hid the boundary instead of reporting staleness",
        )
        assertEquals(listOf(ROW_A to ATT_A), blob.deletes.toList(), "precondition: the delete happened")
        assertTrue(rpc.purges.isEmpty(), "the failed purge was recorded")
        // The current-generation control for this shape is ASGF-7, which still asserts that a purge
        // failure on a live generation stays swallowed and reported as Applied.
    }

    @Test
    fun `ASGF-16 an all-skipped sweep after a boundary is not a success`() = runTest(timeout = TIMEOUT) {
        setUpFixture()
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A, ROW_B to ATT_B)
        val parked = ParkGate()
        rpc.parkOnList = parked
        val blob = RecordingBlobTransport()
        val syncService = service(rpc, blob)
        val token = LocalCommitGate.capture(UID)

        val sweep = async {
            syncService.sweepPendingDeletedAttachments(
                commitToken = token,
                metadataIdentity = testIdentity(UID),
                skipNoteId = { true },
            )
        }
        parked.awaitParked()
        LocalCommitGate.isolate { }
        parked.release()

        assertEquals(
            LocalCommitResult.StaleGeneration,
            sweep.await(),
            "an all-skipped sweep reported success after the generation had moved on",
        )
        assertTrue(blob.deletes.isEmpty(), "a skipped row was deleted anyway")
        assertTrue(rpc.purges.isEmpty(), "a skipped row was purged anyway")

        // Control: all rows skipped on a current generation stays a no-op success.
        assertEquals(
            LocalCommitResult.Applied(Unit),
            syncService.sweepPendingDeletedAttachments(
                commitToken = LocalCommitGate.capture(UID),
                metadataIdentity = testIdentity(UID),
                skipNoteId = { true },
            ),
            "an all-skipped sweep on a current generation stopped being a no-op success",
        )
    }

    // ---- fixtures ----

    private fun service(rpc: FakeRpcClient, blob: RecordingBlobTransport) = AttachmentSyncService(
        blobTransport = blob,
        metadata = SupabaseAttachmentMetadata(rpc),
        localStorage = RecordingLocalStorage(),
        noteDao = noteDao,
        staging = staging,
        ownerIdProvider = { currentUid },
        attachmentsEnabled = { true },
    )

    private fun engine(
        syncService: AttachmentSyncService,
        transport: IdentityBoundNoteTransport = FakeCloudNoteTransport(),
    ) = NoteSyncEngine(
        transport = transport,
        remoteIdentityProvider = testRemoteIdentityProvider { currentUid },
        noteDao = noteDao,
        labelDao = labelDao,
        syncStateStore = stateStore,
        uidProvider = { Result.success(currentUid) },
        platform = "desktop",
        now = { NOW },
        attachmentSync = syncService,
        localCommitTokenProvider = { LocalCommitGate.captureCoherent { currentUid } },
    )

    /** Serves the pending-deleted listing and records the metadata purges the sweep issues. */
    private class FakeRpcClient : SupabaseRpcClient {
        private val pending = mutableListOf<Pair<String, String>>()
        private var purgeFailure: String? = null

        val purges = mutableListOf<Pair<String, String>>()
        var listed = false
            private set

        /** Set to park the listing, so a test can move the generation while it is in flight. */
        var parkOnList: ParkGate? = null

        /** Set to park the first purge, for the same reason. */
        var parkOnPurge: ParkGate? = null

        /** Set to make the listing throw. The sweep has always swallowed that, and still does. */
        var listFailure: Throwable? = null

        private var parkedPurge = false

        fun withPending(vararg rows: Pair<String, String>): FakeRpcClient {
            pending += rows.toList()
            return this
        }

        fun failPurgeFor(attachmentId: String): FakeRpcClient {
            purgeFailure = attachmentId
            return this
        }

        override suspend fun callRpc(functionName: String, body: JsonObject): JsonObject {
            val attachmentId = body["p_attachment_id"]?.toString()?.trim('"')
            if (functionName == "purge_deleted_note_attachment") {
                if (parkOnPurge != null && !parkedPurge) {
                    parkedPurge = true
                    parkOnPurge?.park()
                }
                if (attachmentId == purgeFailure) {
                    error("purge_deleted_note_attachment failed")
                }
            }
            purges += (attachmentId ?: "") to (body["p_note_id"]?.toString()?.trim('"') ?: "")
            return JsonObject(emptyMap())
        }

        /**
         * R17: production metadata traffic is identity-bound. The lane's subject is generation
         * ordering, not identity, so this records nothing and delegates to the live implementation —
         * but it must exist, because the contract's identity-bound default fails closed rather than
         * falling back to the live session.
         */
        /** R17: mutations reach the fake through the identity-bound overload; record them as before. */
        override suspend fun callRpc(
            identity: OperationRemoteIdentity,
            functionName: String,
            body: JsonObject,
        ): JsonObject = callRpc(functionName, body)

        override suspend fun callRpcElement(
            identity: OperationRemoteIdentity,
            functionName: String,
            body: JsonObject,
        ): JsonElement = callRpcElement(functionName, body)

        override suspend fun callRpcElement(functionName: String, body: JsonObject): JsonElement {
            listed = true
            parkOnList?.park()
            listFailure?.let { throw it }
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

    private class RecordingBlobTransport(
        private val parkOnDelete: ParkGate? = null,
        private val parkOnCaptureContext: ParkGate? = null,
        private val failDeleteFor: String? = null,
    ) : AttachmentBlobTransport {
        val deletes = mutableListOf<Pair<String, String>>()
        private var parkedDelete = false

        override suspend fun captureContext(): AttachmentRemoteContext {
            if (parkOnCaptureContext != null) parkOnCaptureContext.park()
            return AttachmentRemoteContext(ownerId = UID, accessToken = "token")
        }

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

        override suspend fun delete(context: AttachmentRemoteContext, noteId: String, attachmentId: String) {
            deletes += noteId to attachmentId
            if (parkOnDelete != null && !parkedDelete) {
                parkedDelete = true
                parkOnDelete.park()
            }
            if (noteId == failDeleteFor) error("blob delete failed")
        }
    }

    private class RecordingLocalStorage : AttachmentLocalStorage {
        override fun persistImageBytes(bytes: ByteArray, extension: String): String? = null

        override fun readBytes(storagePath: String): ByteArray? = null

        override fun deleteIfLocal(storagePath: String) {}
    }

    /** Parks one remote call so a test can move the generation while it is in flight. */
    private class ParkGate {
        private val parked = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        suspend fun awaitParked() = parked.await()

        fun release() {
            released.complete(Unit)
        }

        suspend fun park() {
            parked.complete(Unit)
            released.await()
        }
    }

    private companion object {
        const val UID = "same-user"
        const val ROW_A = "101"
        const val ROW_B = "102"
        const val ATT_A = "att-a"
        const val ATT_B = "att-b"

        const val NOW = 2_000_000_000_000L
        val TIMEOUT = 60.seconds
    }
}
