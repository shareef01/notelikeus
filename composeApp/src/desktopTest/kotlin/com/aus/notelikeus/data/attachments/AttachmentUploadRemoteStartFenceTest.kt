package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentBlobUploadResult
import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.sync.DatasetEpochAuthority
import com.aus.notelikeus.data.sync.FakeCloudNoteTransport
import com.aus.notelikeus.data.sync.FakeLabelDao
import com.aus.notelikeus.data.sync.FakeNoteDao
import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import com.aus.notelikeus.data.sync.InMemoryDatasetEpochStore
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The blob upload's **generation** authority — R14, closing F-2.
 *
 * [AttachmentRemoteIdentityFenceTest] pins *whose* account a remote attachment operation executes
 * under: `AttachmentRemoteContext` is snapshotted once and passed explicitly, so a delayed upload
 * cannot adopt whatever session is live when the request finally goes out. That is a fix about
 * identity, and it says nothing about **time**: nothing stopped attachment work that began in
 * generation N from starting a *new* upload after N had been replaced. The identity it used was the
 * originating one — which is why the result was an A-owned object written for a dataset that no
 * longer exists, rather than an obvious cross-account bug.
 *
 * The model these pin, one grant per actual invocation:
 *
 * ```
 * originating LocalCommitToken  ->  threaded in, never recaptured
 * each real blobTransport.upload ->  its own RemoteMutationAuthorization
 * already-issued upload         ->  may stand (F-10, unchanged)
 * not-yet-issued upload         ->  does not start once the boundary landed
 * ```
 *
 * AURSF-1 and AURSF-4 are the recorded pre-fix reds and drive the *engine* rather than the service,
 * so the same test body was the red and is the green; the rest were written against the fixed path.
 *
 * No sleeps. Every window is a `CompletableDeferred` park, and each boundary is landed from a real
 * second thread, so nothing depends on a single-threaded schedule.
 */
class AttachmentUploadRemoteStartFenceTest {

    private var currentUid: String = UID
    private var noteDao = FakeNoteDao()
    private var stateStore = FakeNoteSyncStateStore()
    private lateinit var staging: MemoryStagingStore
    private lateinit var blob: RecordingBlobTransport
    private lateinit var cloud: FakeCloudNoteTransport

    private fun setUpFixture() {
        currentUid = UID
        noteDao = FakeNoteDao()
        stateStore = FakeNoteSyncStateStore().apply { setLastMergedUserId(UID) }
        staging = MemoryStagingStore()
        blob = RecordingBlobTransport()
        cloud = FakeCloudNoteTransport()
    }

    // ---- AURSF-1 / RED-F-2A: isolation before the first upload ----

    /**
     * Pre-fix behaviour, recorded before the fix:
     * `expected uploads: [] but was [att-1]` — the operation's generation N was replaced while its
     * attachment work sat in ordinary code between the identity snapshot and the request, and the
     * upload started anyway.
     *
     * Post-fix: the capture is followed by an authoritative generation decision, isolation wins
     * that decision, and no upload is issued at all — the per-upload grant is the second line of
     * defence behind it.
     */
    @Test
    fun `AURSF-1 isolation before the first blob grant starts no upload`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            seedLocalNote(NOTE_ID, ATTACHMENT_1)
            blob.parkCapture(number = 1)

            val engine = engine()
            val upload = async(Dispatchers.IO) { engine.uploadAllNotes() }
            blob.awaitCaptureParked()

            // Generation N's attachment work is parked in ordinary code, past its identity snapshot.
            withContext(Dispatchers.IO) {
                LocalCommitGate.isolate { seedReplacementGeneration() }
            }
            blob.releaseCapture()
            val result = upload.await()

            assertEquals(UID, currentUid, "precondition: the uid never changed, only the generation")
            assertTrue(result.isSuccess, "the refusal surfaced as a failure: ${result.exceptionOrNull()}")
            assertEquals(
                emptyList(),
                blob.uploads.map { it.attachmentId },
                "attachment work from a replaced generation started a blob upload",
            )
        }

    // ---- AURSF-2: the authorization-first order ----

    /**
     * A grant obtained *before* the boundary stands. That asymmetry is the accepted application-level
     * issuance model (F-10), and R14 narrows it rather than removing it: what it removes is the
     * *un*authorized start, not the already-issued one.
     *
     * The second half of the assertion is the other half of the contract — the request is in flight
     * with `LocalCommitGate` released, so account isolation can never be serialized behind a round
     * trip.
     */
    @Test
    fun `AURSF-2 an upload whose grant was already issued may stand`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            seedLocalNote(NOTE_ID, ATTACHMENT_1)
            blob.parkUpload(number = 1)

            val engine = engine()
            val upload = async(Dispatchers.IO) { engine.uploadAllNotes() }
            blob.awaitUploadParked()

            assertFalse(
                LocalCommitGate.mutex.isLocked,
                "the blob upload was issued while LocalCommitGate was held",
            )
            var isolated = false
            withContext(Dispatchers.IO) { LocalCommitGate.isolate { isolated = true } }
            assertTrue(isolated, "isolation could not complete while the upload was outstanding")

            blob.releaseUpload()
            assertTrue(upload.await().isSuccess)

            assertEquals(
                listOf(ATTACHMENT_1),
                blob.uploads.map { it.attachmentId },
                "the already-authorized upload should have been issued exactly once",
            )
        }

    // ---- AURSF-3: the same-uid generation case, on the service itself ----

    /**
     * The same case one layer down, driven at the service so the grant's own boundary is what is
     * exercised: the uid never moves, only the generation does, and the upload is refused.
     */
    @Test
    fun `AURSF-3 a same-uid new generation refuses the first upload`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            staging.seed(ATTACHMENT_1, UID, NOTE_ID, byteArrayOf(1, 2, 3), "image/png")
            val token = LocalCommitGate.capture(UID)
            blob.parkCapture(number = 1)
            val sync = service()

            val upload = async(Dispatchers.IO) {
                sync.syncNoteAttachments(noteWith(ATTACHMENT_1), token)
            }
            blob.awaitCaptureParked()
            withContext(Dispatchers.IO) { LocalCommitGate.isolate { } }
            blob.releaseCapture()
            val result = upload.await()

            assertEquals(UID, currentUid, "precondition: the uid is identical across the boundary")
            assertIs<LocalCommitResult.StaleGeneration>(
                result,
                "uid equality rescued an upload whose generation had been replaced",
            )
            assertEquals(emptyList(), blob.uploads, "a refused upload still reached the wire")
        }

    // ---- AURSF-4 / RED-F-2B: the boundary between two uploads ----

    /**
     * Two real remote attachments, so there is a boundary *between* them rather than only before the
     * first. A's grant is already issued when the boundary lands, so A's upload stands — the
     * application-level issuance model is unchanged — and B must not start.
     *
     * Pre-fix behaviour, recorded before the fix:
     * `expected uploads: [att-1] but was [att-1, att-2]` — nothing distinguished "already issued"
     * from "not yet started", because there was no authorization to win.
     */
    @Test
    fun `AURSF-4 a boundary between two uploads suppresses the later one`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            seedLocalNote(NOTE_ID, ATTACHMENT_1, ATTACHMENT_2)
            blob.parkUpload(number = 1)

            val engine = engine()
            val upload = async(Dispatchers.IO) { engine.uploadAllNotes() }
            blob.awaitUploadParked()

            withContext(Dispatchers.IO) {
                LocalCommitGate.isolate { seedReplacementGeneration() }
            }
            blob.releaseUpload()
            val result = upload.await()

            assertEquals(
                listOf(ATTACHMENT_1),
                blob.uploads.map { it.attachmentId },
                "the first, already-authorized upload should stand and the later one should not start",
            )
            assertTrue(result.isSuccess, "the refusal surfaced as a failure: ${result.exceptionOrNull()}")
        }

    // ---- AURSF-15 / R16.1: the missing window behind the batch's one decision ----

    /**
     * The upload family's version of the primitive's window. `syncNoteAttachments` decides the
     * generation once, before the attachment loop, and the loop then suspends inside each upload — so
     * the second attachment's grant is the first authority request *after* the device can have entered
     * `Isolating` without the generation moving yet. The marker is persisted while the first upload is
     * in flight, and the generation is asserted untouched, so nothing here is a generation mismatch.
     *
     * Pre-fix this is red: `uploads` becomes `[att-1, att-2]` — the later upload is authorized on a
     * generation match alone, for a dataset the device has already left.
     */
    @Test
    fun `AURSF-15 an unfinished isolation refuses the later upload's grant`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            seedLocalNote(NOTE_ID, ATTACHMENT_1, ATTACHMENT_2)
            val authority = DatasetEpochAuthority(InMemoryDatasetEpochStore())
            LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
            blob.parkUpload(number = 1)

            val engine = engine()
            val upload = async(Dispatchers.IO) { engine.uploadAllNotes() }
            blob.awaitUploadParked()   // the first upload was granted and is on the wire

            val generation = LocalCommitGate.currentGeneration()
            withContext(Dispatchers.IO) { authority.beginIsolation() }
            assertEquals(
                generation,
                LocalCommitGate.currentGeneration(),
                "precondition: the marker alone must not move the local generation",
            )
            assertTrue(authority.isIsolationIncomplete(), "precondition: the transition is in flight")
            blob.releaseUpload()
            val result = upload.await()

            assertEquals(
                listOf(ATTACHMENT_1),
                blob.uploads.map { it.attachmentId },
                "the already-issued upload stands, and no later one may be authorized while the device " +
                    "is between datasets",
            )
            assertTrue(result.isSuccess, "the refusal surfaced as a failure: ${result.exceptionOrNull()}")
        }

    // ---- AURSF-5: the orphan scenario ----

    /**
     * An upload that was already authorized completes; the boundary lands behind it; the *next*
     * generation decision refuses before the note batch.
     *
     * The note batch was already protected here before R14 — the engine's own `putNotes` grant (R13A)
     * sits after the attachment pass and refuses on its own. What R14 adds is that the attachment
     * upload itself is now gated; what it does **not** do is un-issue an upload that was already
     * authorized, so the R2 object from that upload can remain orphaned. That residual is inherent to
     * the accepted issuance model and is reported rather than papered over.
     */
    @Test
    fun `AURSF-5 an upload that completed across the boundary does not carry the note batch with it`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            seedLocalNote(NOTE_ID, ATTACHMENT_1)
            blob.parkUpload(number = 1)

            val engine = engine()
            val upload = async(Dispatchers.IO) { engine.uploadAllNotes() }
            blob.awaitUploadParked()
            withContext(Dispatchers.IO) {
                LocalCommitGate.isolate { seedReplacementGeneration() }
            }
            blob.releaseUpload()
            val result = upload.await()

            assertEquals(
                listOf(ATTACHMENT_1),
                blob.uploads.map { it.attachmentId },
                "precondition: the already-issued upload is the one that stands",
            )
            assertEquals(
                emptyList(),
                cloud.notes.keys.toList(),
                "a stale attachment continuation carried the note batch over the boundary",
            )
            assertTrue(result.isSuccess, "the refusal surfaced as a failure: ${result.exceptionOrNull()}")
        }

    // ---- AURSF-6: the valid case is unchanged ----

    @Test
    fun `AURSF-6 a valid multi-upload is issued once per attachment, in order`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            seedLocalNote(NOTE_ID, ATTACHMENT_1, ATTACHMENT_2)

            val result = engine().uploadAllNotes()

            assertTrue(result.isSuccess, "a valid upload failed: ${result.exceptionOrNull()}")
            assertEquals(1, result.getOrThrow(), "the engine did not report the uploaded note")
            assertEquals(
                listOf(ATTACHMENT_1, ATTACHMENT_2),
                blob.uploads.map { it.attachmentId },
                "each attachment should have been uploaded exactly once, in order",
            )
            assertEquals(
                listOf(NOTE_ID.toString(), NOTE_ID.toString()),
                blob.uploads.map { it.noteId },
            )
            assertEquals(setOf(NOTE_ID), cloud.notes.keys, "the note batch did not reach the cloud")
        }

    // ---- AURSF-7: failure semantics are unchanged ----

    /**
     * An upload that fails is a failure, not a boundary. Aliasing it to `StaleGeneration` would turn
     * "the Worker is down" into "this dataset is gone", which the next sync must be able to retry.
     */
    @Test
    fun `AURSF-7 an upload failure stays a failure and is not reported as stale`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            seedLocalNote(NOTE_ID, ATTACHMENT_1)
            blob.failure = WorkerDown("worker down")

            val outcome = runCatching {
                service().syncNoteAttachments(noteWith(ATTACHMENT_1), LocalCommitGate.capture(UID))
            }

            assertTrue(
                outcome.isFailure,
                "an upload failure was folded into a result: ${outcome.getOrNull()}",
            )
            assertIs<WorkerDown>(
                outcome.exceptionOrNull(),
                "the transport failure was replaced by ${outcome.exceptionOrNull()}",
            )
            assertEquals(listOf(ATTACHMENT_1), blob.uploads.map { it.attachmentId })
        }

    // ---- AURSF-8: nothing that is not a remote invocation is authorized ----

    /**
     * An attachment that produces no transport invocation must not take the operation's authority.
     *
     * What is asserted is the observable consequence: the batch is still `Applied` — nothing about it
     * was refused — and nothing reached the wire. An `r2:` attachment and a local file that is
     * provably gone both pass through untouched, which is why the engine only ever authorizes calls it
     * actually makes.
     */
    @Test
    fun `AURSF-8 already-remote and unrecoverable attachments take no authorization`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            val note = Note(
                id = NOTE_ID,
                title = "A's note",
                content = "A's body",
                timestamp = 1_000L,
                color = 0,
                attachments = listOf(
                    // Already remote: nothing to invoke.
                    Attachment(
                        id = ATTACHMENT_1,
                        noteId = NOTE_ID,
                        storagePath = "r2:owners/$UID/notes/$NOTE_ID/$ATTACHMENT_1",
                        type = "image",
                        mimeType = "image/png",
                        sizeBytes = 3,
                    ),
                    // A local file that is provably gone: dropped, and nothing to invoke.
                    Attachment(
                        id = ATTACHMENT_2,
                        noteId = NOTE_ID,
                        storagePath = "file:/nowhere/$ATTACHMENT_2.png",
                        type = "image",
                        mimeType = "image/png",
                        sizeBytes = 3,
                    ),
                ),
            )

            val result = service().syncNoteAttachments(note, LocalCommitGate.capture(UID))

            assertIs<LocalCommitResult.Applied<Note>>(result)
            assertEquals(
                listOf(ATTACHMENT_1),
                result.value.attachments.map { it.id },
                "the unrecoverable local file was not dropped",
            )
            assertEquals(emptyList(), blob.uploads, "a non-invocation reached the wire")
            assertEquals(emptyList(), blob.deletes, "a non-invocation reached the wire")
        }

    // ---- AURSF-9: one originating token, and it is not recaptured ----

    /**
     * The service's attachment paths must take the caller's token and nothing else.
     *
     * A behavioural test can only show this for the paths it drives, so this reads the source the
     * build actually compiles: no attachment sync entry point may mint an authority of its own, and
     * the `LocalCommitTokenProvider` seam must not appear in the file at all.
     */
    @Test
    fun `AURSF-9 no attachment sync entry point recaptures the generation`() {
        val source = attachmentServiceSource()

        assertFalse(
            source.contains("LocalCommitTokenProvider"),
            "the attachment service captured a generation of its own instead of taking the caller's",
        )
        assertFalse(
            Regex("""LocalCommitGate\.capture\(""").containsMatchIn(source),
            "the attachment service minted a generation of its own instead of taking the caller's",
        )
        assertEquals(
            3,
            Regex(
                """suspend fun (syncNoteAttachments|syncNotesAttachments|reconcileStagedAttachments)\(""",
            ).findAll(source).count(),
            "the attachment sync entry points moved; recount the token-threading inventory",
        )
    }

    // ---- AURSF-14: no upload site can reach the transport ungranted ----

    /**
     * Every `blobTransport.upload` in the service is a *granted* invocation.
     *
     * AURSF-1..AURSF-4 prove the paths a test can drive; this pins the property for the ones it
     * cannot, and for the next edit: the upload site is inside a consumed
     * `RemoteMutationAuthorization`, the token it authorizes against is required rather than
     * optional, and there is no `commitToken == null` branch left for an un-authorized caller to
     * arrive through. That nullable parameter was the one shape of F-2 a per-invocation grant cannot
     * cover — with no generation there is nothing to take a grant from — so its absence is part of
     * the closure, not a style choice.
     */
    @Test
    fun `AURSF-14 every blob upload site is a granted invocation`() {
        val source = attachmentServiceSource()
        val uploadSites = Regex("""blobTransport\.upload\(""").findAll(source).count()
        val grantedSites = Regex("""consumeOnce \{\s*blobTransport\.upload\(""").findAll(source).count()

        assertTrue(uploadSites >= 1, "precondition: the upload site moved")
        assertEquals(
            uploadSites,
            grantedSites,
            "a blob upload site is reachable without consuming an authorization",
        )
        assertFalse(
            source.contains("commitToken: LocalCommitToken?"),
            "an attachment entry point went back to taking an optional generation",
        )
        assertFalse(
            source.contains("commitToken == null"),
            "an upload path exists for a caller with no generation to authorize against",
        )
    }

    // ---- AURSF-10: no network under the gate ----

    @Test
    fun `AURSF-10 no blob upload runs under LocalCommitGate`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            staging.seed(ATTACHMENT_1, UID, NOTE_ID, byteArrayOf(1, 2, 3), "image/png")
            blob.parkUpload(number = 1)
            val sync = service()

            val upload = async(Dispatchers.IO) {
                sync.syncNoteAttachments(noteWith(ATTACHMENT_1), LocalCommitGate.capture(UID))
            }
            blob.awaitUploadParked()

            assertFalse(LocalCommitGate.mutex.isLocked, "the gate was held across the upload")
            var isolated = false
            withContext(Dispatchers.IO) { LocalCommitGate.isolate { isolated = true } }
            assertTrue(isolated, "isolation could not complete while the upload was outstanding")
            blob.releaseUpload()
            assertIs<LocalCommitResult.Applied<Note>>(upload.await())
        }

    // ---- R14.1: the temporal order of the context capture and the generation decision ----

    /**
     * A **different-account** context captured across the boundary is a *stale generation* first.
     *
     * `captureContext()` suspends and may refresh credentials, so the session can move — including to
     * another account — while it is outstanding. When that happens, the context that comes back
     * describes the *new* session, and comparing it against the originating token would report a
     * coherence violation. But the deeper fact is that the generation this operation belongs to no
     * longer exists: reporting the mismatch would mask the boundary behind an invariant error, and
     * reporting it as an invariant error is exactly what lets a *swallowed* failure carry on.
     *
     * Required temporal ordering: capture, authoritative generation decision, and only then the
     * context/owner coherence check.
     */
    @Test
    fun `AURSF-11 a context captured for another account across the boundary reports stale, not a mismatch`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            staging.seed(ATTACHMENT_1, UID, NOTE_ID, byteArrayOf(1, 2, 3), "image/png")
            val token = LocalCommitGate.capture(UID)
            blob.parkCapture(number = 1)

            val upload = async(Dispatchers.IO) {
                service().syncNoteAttachments(noteWith(ATTACHMENT_1), token)
            }
            blob.awaitCaptureParked()

            // The boundary: a new generation, and account B is what the in-flight capture will
            // therefore read when it resumes.
            withContext(Dispatchers.IO) {
                LocalCommitGate.isolate {
                    currentUid = UID_B
                    blob.context = AttachmentRemoteContext(ownerId = UID_B, accessToken = TOKEN_B)
                }
            }
            blob.releaseCapture()
            val outcome = runCatching { upload.await() }

            assertEquals(UID_B, currentUid, "precondition: the boundary moved the account, not just the generation")
            assertTrue(
                outcome.isSuccess,
                "the extinct generation surfaced as an invariant failure instead of a refusal: " +
                    "${outcome.exceptionOrNull()}",
            )
            assertIs<LocalCommitResult.StaleGeneration>(
                outcome.getOrNull(),
                "the boundary was masked: an extinct generation must be refused as stale",
            )
            assertEquals(emptyList(), blob.uploads, "a refused context still reached the wire")
        }

    /**
     * The control that keeps the two apart: a **still-current** generation whose context belongs to
     * another account is a genuine incoherence, not a boundary, and must stay a hard failure.
     *
     * Without this, "always report stale" would pass the case above while silently downgrading a real
     * context corruption into an ordinary refusal.
     */
    @Test
    fun `AURSF-12 a current generation whose context belongs to another account is still a hard failure`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            staging.seed(ATTACHMENT_1, UID, NOTE_ID, byteArrayOf(1, 2, 3), "image/png")
            val token = LocalCommitGate.capture(UID)
            // No boundary at all: the generation the token names is still current. Only the captured
            // context is wrong, which is the shape a corrupted or mis-wired context provider produces.
            blob.context = AttachmentRemoteContext(ownerId = UID_B, accessToken = TOKEN_B)

            val outcome = runCatching {
                service().syncNoteAttachments(noteWith(ATTACHMENT_1), token)
            }

            assertTrue(
                outcome.isFailure,
                "an incoherent context under a current generation was accepted: ${outcome.getOrNull()}",
            )
            assertIs<IllegalStateException>(
                outcome.exceptionOrNull(),
                "the incoherence was reported as something other than an invariant failure: " +
                    "${outcome.exceptionOrNull()}",
            )
            assertEquals(emptyList(), blob.uploads, "an incoherent context still reached the wire")
        }

    /** The same-uid half of the boundary: only the generation moves, and it is still a refusal. */
    @Test
    fun `AURSF-13 a same-uid generation change across the context capture is reported stale`() =
        runTest(timeout = TIMEOUT) {
            setUpFixture()
            staging.seed(ATTACHMENT_1, UID, NOTE_ID, byteArrayOf(1, 2, 3), "image/png")
            val token = LocalCommitGate.capture(UID)
            blob.parkCapture(number = 1)

            val upload = async(Dispatchers.IO) {
                service().syncNoteAttachments(noteWith(ATTACHMENT_1), token)
            }
            blob.awaitCaptureParked()
            withContext(Dispatchers.IO) { LocalCommitGate.isolate { } }
            blob.releaseCapture()
            val outcome = runCatching { upload.await() }

            assertEquals(UID, currentUid, "precondition: the uid is identical across the boundary")
            assertTrue(outcome.isSuccess, "the refusal surfaced as a failure: ${outcome.exceptionOrNull()}")
            assertIs<LocalCommitResult.StaleGeneration>(outcome.getOrNull())
            assertEquals(emptyList(), blob.uploads, "a refused context still reached the wire")
        }

    // ---- fixtures ----

    /** The boundary dataset: same account and same staging namespace, with its own rows and state. */
    @AfterTest
    fun takeTheQuarantineBackDown() {
        // The quarantine is process-wide by design — every local commit and every grant consults it —
        // so a lane that installs one has to take it back down.
        LocalCommitGate.installDatasetAuthorityQuarantine(null)
    }

    private fun seedReplacementGeneration() {
        stateStore.clear()
        stateStore.setLastMergedUserId(UID)
        noteDao.notes.clear()
    }

    private fun seedLocalNote(id: Long, vararg attachmentIds: String) {
        noteDao.notes[id] = noteWith(attachmentIds = attachmentIds).toNoteEntity()
        for (attachmentId in attachmentIds) {
            staging.seed(attachmentId, UID, id, byteArrayOf(1, 2, 3), "image/png")
        }
    }

    private fun noteWith(vararg attachmentIds: String) = Note(
        id = NOTE_ID,
        title = "A's note",
        content = "A's body",
        timestamp = 1_000L,
        color = 0,
        attachments = attachmentIds.map { pending(it) },
    )

    private fun pending(attachmentId: String) = Attachment(
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

    /** In-memory staging, so a test can decide exactly what bytes exist for a pending attachment. */
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

    /**
     * Records every blob upload that actually started, together with the identity and note it
     * carried, and can park the Nth identity snapshot or the Nth upload so a boundary can be landed
     * in that window from a real second thread.
     */
    private class RecordingBlobTransport : AttachmentBlobTransport {
        data class Upload(val attachmentId: String, val noteId: String, val ownerId: String)

        val captures = mutableListOf<AttachmentRemoteContext>()
        val uploads = mutableListOf<Upload>()
        val deletes = mutableListOf<Pair<String, String>>()
        var failure: Throwable? = null
        var context: AttachmentRemoteContext = AttachmentRemoteContext(ownerId = UID, accessToken = TOKEN)

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
            uploads += Upload(attachmentId, noteId, context.ownerId)
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
        ) {
            deletes += noteId to attachmentId
        }
    }

    private class WorkerDown(message: String) : RuntimeException(message)

    private companion object {
        const val UID = "same-user"
        const val UID_B = "account-b"
        const val TOKEN = "token-a"
        const val TOKEN_B = "token-b"
        const val NOTE_ID = 42L
        const val ATTACHMENT_1 = "att-1"
        const val ATTACHMENT_2 = "att-2"
        const val NOW = 1_000_000L

        val TIMEOUT = 60.seconds
    }
}
