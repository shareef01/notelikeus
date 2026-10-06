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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The pending-delete attachment sweep's *remote-start authority* — R16, closing F-6.
 *
 * The sweep's first fencing pass (D11 3D.3H / 3D.3H.1) made every **outcome** right: a token that is
 * stale at any decision refuses, and an empty, failed, skipped, or completed-across-the-boundary
 * sweep reports staleness instead of a false success. What that pass deliberately left alone is the
 * authority for the calls themselves. `validateGeneration` is a *moment*: it is a separate critical
 * section from the transport invocation that follows it, and the statements in between are ordinary
 * code — on desktop the sync queue runs on `Dispatchers.IO` while sign-out isolates from its own
 * context, so a genuine second thread can acquire the gate and advance the generation inside that
 * gap. The sweep was the last remote-mutating path in the app that still started requests that way;
 * the attachment upload path (F-2/F-3) and the note RPCs (F-1) already take a one-shot
 * `LocalCommitGate.RemoteMutationAuthorization` per actual invocation.
 *
 * R16 gives every actual invocation its own grant, from the sweep's **originating** token: one for
 * each object deletion, one for the metadata purge, each taken immediately before the call it
 * authorizes, each consumed exactly once. The two are deliberately separate — a deletion that
 * completed does not authorize its purge, and a purge is not covered by the deletion's grant. The
 * temporal checks stay exactly where 3D.3H.1 put them; the two mechanisms answer different
 * questions, and these lanes pin both:
 *
 *  - F6-1..F6-5, F6-14, F6-15 — what a boundary landing behind a suspension does to each mutation,
 *    including the accepted F-10 asymmetry: the grant *is* atomic, the request is not, so a mutation
 *    authorized before the boundary may still complete;
 *  - F6-6..F6-9 — the historical success policies, and the no-op paths that must not take an
 *    authorization at all. F6-9's failed-listing half was corrected by F-11/R22: a listing failure is
 *    no longer a swallowed no-op success, because "the server could not be asked" is not "the server
 *    says nothing is pending". The no-grant, no-request property it also pins is unchanged;
 *  - F6-10, F6-16 — the structural half: the originating token is reused and never re-minted, and
 *    every sweep mutation site is a granted invocation (with the dead tokenless API's bare calls
 *    proven *outside* the sweep, so F-6 stays separately identifiable);
 *  - F6-11..F6-13 — no network in the gate, and the R15.3 interaction: while a dataset isolation is
 *    incomplete, the sweep obtains no authority at all.
 *
 * Deterministic `CompletableDeferred` barriers only: no sleeps and no fixed delays, just this
 * package's usual bounded `runTest` timeout as a guard.
 *
 * **What the reds showed.** Reverting the two grants is the exact pre-fix shape of this path, and it
 * turns *only* the two structural lanes red (F6-10, F6-16): the temporal checks 3D.3H.1 added already
 * produce every outcome the behavioral lanes assert, so the gap F-6 describes is not an outcome at
 * all — it is the *authority* for a call, which lives in the window between a decision and the
 * invocation that follows it. Those outcome-level reds appear only when the decisions are reverted as
 * well, and then they are literal: F6-1 `expected:<[]> but was:<[(101, att-a)]>`, F6-3
 * `expected:<[(101, att-a)]> but was:<[(101, att-a), (102, att-b), (103, att-c)]>`, F6-2
 * `expected:<[]> but was:<[(att-a, 101)]>`, and F6-13 `expected:<[]> but was:<[(101, att-a)]>` for the
 * unfinished-isolation case. These lanes therefore pin the authority itself: exact invocation counts,
 * the grant-facing structure, and the gate being free while a call is in flight.
 */
class PendingAttachmentDeleteRemoteStartFenceTest {

    private lateinit var tempDir: File
    private lateinit var staging: FileAttachmentStagingStore

    private var currentUid: String = UID
    private var noteDao = FakeNoteDao()

    @BeforeTest
    fun setUpDir() {
        tempDir = Files.createTempDirectory("notelikeus-sweep-remote-start-test").toFile()
        staging = FileAttachmentStagingStore(
            root = File(tempDir, "staging").absolutePath.toPath(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        currentUid = UID
        noteDao = FakeNoteDao()
    }

    @AfterTest
    fun tearDownDir() {
        // The quarantine is process-wide by design — every local commit consults it — so a lane that
        // installs one has to take it back down.
        LocalCommitGate.installDatasetAuthorityQuarantine(null)
        tempDir.deleteRecursively()
    }

    // ---- F6-1: isolation before the first blob delete ----

    @Test
    fun `F6-1 isolation before the first blob delete issues no request`() = runTest(timeout = TIMEOUT) {
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
        val parked = ParkGate()
        // The listing is back; the sweep's one identity snapshot is in flight. This is the last
        // suspension before the first request the sweep would make.
        val blob = RecordingBlobTransport(parkOnCaptureContext = parked)
        val token = LocalCommitGate.capture(UID)

        val sweep = async { service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)) }
        parked.awaitParked()

        LocalCommitGate.isolate { }
        parked.release()

        assertTrue(rpc.listed, "precondition: the listing ran, so this is not an early no-op")
        assertEquals(
            LocalCommitResult.StaleGeneration,
            sweep.await(),
            "a sweep whose dataset was replaced while it parked reported something other than staleness",
        )
        assertEquals(emptyList(), blob.deletes.toList(), "a blob deletion was issued for a replaced generation")
        assertEquals(emptyList(), rpc.purges.toList(), "a metadata purge was issued for a replaced generation")
    }

    // ---- F6-2 / F6-4: the delete's grant is atomic, the purge's is a separate one ----

    @Test
    fun `F6-2 an already-issued blob delete stands and the fresh purge does not start`() =
        runTest(timeout = TIMEOUT) {
            val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
            val parked = ParkGate()
            val blob = RecordingBlobTransport(parkOnDelete = parked)
            val token = LocalCommitGate.capture(UID)

            val sweep = async { service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)) }
            parked.awaitParked()   // this deletion was authorized under the sweep's generation, in flight

            LocalCommitGate.isolate { }
            parked.release()

            assertEquals(
                LocalCommitResult.StaleGeneration,
                sweep.await(),
                "a deletion that completed across the boundary was still reported as success",
            )
            assertEquals(
                listOf(ROW_A to ATT_A),
                blob.deletes.toList(),
                "the deletion whose grant preceded the boundary must stand — F-10 unchanged",
            )
            assertEquals(
                emptyList(),
                rpc.purges.toList(),
                "the purge is a separate mutation and had no grant of its own when the boundary landed",
            )
        }

    @Test
    fun `F6-4 a boundary behind a record's deletion leaves the whole purge phase at zero`() =
        runTest(timeout = TIMEOUT) {
            val rpc = FakeRpcClient().withPending(ROW_A to ATT_A, ROW_B to ATT_B)
            val parked = ParkGate()
            val blob = RecordingBlobTransport(parkOnDelete = parked)
            val token = LocalCommitGate.capture(UID)

            val sweep = async { service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)) }
            parked.awaitParked()

            LocalCommitGate.isolate { }
            parked.release()

            assertEquals(LocalCommitResult.StaleGeneration, sweep.await(), "a stale sweep reported success")
            assertEquals(
                listOf(ROW_A to ATT_A),
                blob.deletes.toList(),
                "the later record's deletion started although its grant could not be taken",
            )
            // The purge phase is what this lane discriminates: not one purge went out, for the record
            // whose deletion stood or for the record that never started.
            assertEquals(
                emptyList(),
                rpc.purges.toList(),
                "a purge was issued after the boundary landed behind a deletion",
            )
        }

    // ---- F6-3 / F6-15: a boundary between records stops the later ones ----

    @Test
    fun `F6-3 a boundary between records suppresses every later record`() = runTest(timeout = TIMEOUT) {
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A, ROW_B to ATT_B, ROW_C to ATT_C)
        val parked = ParkGate()
        val blob = RecordingBlobTransport(parkOnDelete = parked)
        val token = LocalCommitGate.capture(UID)

        val sweep = async { service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)) }
        parked.awaitParked()   // record A's deletion is in flight, under its own grant

        LocalCommitGate.isolate { }
        parked.release()

        assertEquals(LocalCommitResult.StaleGeneration, sweep.await(), "a stale sweep reported success")
        assertEquals(
            listOf(ROW_A to ATT_A),
            blob.deletes.toList(),
            "a later record's deletion started without a grant from the replacement generation",
        )
        assertEquals(emptyList(), rpc.purges.toList(), "a later record's purge followed a refused grant")
    }

    @Test
    fun `F6-15 a boundary behind a record's purge stops the records after it`() = runTest(timeout = TIMEOUT) {
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A, ROW_B to ATT_B, ROW_C to ATT_C)
        val parked = ParkGate()
        rpc.parkOnPurge = parked
        val blob = RecordingBlobTransport()
        val token = LocalCommitGate.capture(UID)

        val sweep = async { service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)) }
        parked.awaitParked()   // record A's deletion is done; its purge is in flight, under its own grant

        LocalCommitGate.isolate { }
        parked.release()

        assertEquals(LocalCommitResult.StaleGeneration, sweep.await(), "a stale sweep reported success")
        assertEquals(
            listOf(ROW_A to ATT_A),
            blob.deletes.toList(),
            "a later record's deletion started after the boundary landed behind record A's purge",
        )
        assertEquals(
            listOf(ATT_A to ROW_A),
            rpc.purges.toList(),
            "the purge already in flight had to stand, and no later record's purge may start",
        )
    }

    // ---- F6-5: the purge's grant is atomic in the same way ----

    @Test
    fun `F6-5 a purge granted under the sweep's generation stands when the boundary lands behind it`() =
        runTest(timeout = TIMEOUT) {
            val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
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
                "a purge that completed across the boundary was still reported as success",
            )
            assertEquals(listOf(ROW_A to ATT_A), blob.deletes.toList(), "precondition: the deletion happened")
            assertEquals(
                listOf(ATT_A to ROW_A),
                rpc.purges.toList(),
                "the purge whose own grant preceded the boundary must stand",
            )
        }

    // ---- F6-6 / F6-7 / F6-8: success and failure policies are unchanged ----

    @Test
    fun `F6-6 a valid sweep grants, deletes and purges every record in order`() = runTest(timeout = TIMEOUT) {
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A, ROW_B to ATT_B)
        val blob = RecordingBlobTransport()

        val result = service(rpc, blob).sweepPendingDeletedAttachments(LocalCommitGate.capture(UID), testIdentity(UID))

        assertEquals(LocalCommitResult.Applied(Unit), result, "a valid sweep was refused")
        assertEquals(
            listOf(ROW_A to ATT_A, ROW_B to ATT_B),
            blob.deletes.toList(),
            "the sweep did not attempt both records in listing order",
        )
        assertEquals(
            listOf(ATT_A to ROW_A, ATT_B to ROW_B),
            rpc.purges.toList(),
            "each record's purge did not follow its deletion — a shared or reused grant would drop one",
        )
    }

    @Test
    fun `F6-7 a blob-delete failure keeps its existing semantics`() = runTest(timeout = TIMEOUT) {
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A, ROW_B to ATT_B)
        val blob = RecordingBlobTransport(failDeleteFor = ROW_A)

        val result = service(rpc, blob).sweepPendingDeletedAttachments(LocalCommitGate.capture(UID), testIdentity(UID))

        assertEquals(
            LocalCommitResult.Applied(Unit),
            result,
            "a transport failure was turned into a generation refusal",
        )
        assertEquals(
            listOf(ROW_A to ATT_A, ROW_B to ATT_B),
            blob.deletes.toList(),
            "the failed record aborted the sweep instead of moving on",
        )
        assertEquals(
            listOf(ATT_B to ROW_B),
            rpc.purges.toList(),
            "the failed record's metadata was purged, or the following record's was skipped",
        )
    }

    @Test
    fun `F6-8 a purge failure keeps its existing semantics`() = runTest(timeout = TIMEOUT) {
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
        assertTrue(rpc.purges.any { it.second == ROW_B }, "the record after the purge failure was skipped")
    }

    // ---- F6-9: a path that invokes nothing takes no authorization ----

    @Test
    fun `F6-9 the paths that invoke nothing request nothing`() = runTest(timeout = TIMEOUT) {
        val empty = FakeRpcClient()
        val emptyBlob = RecordingBlobTransport()
        assertEquals(
            LocalCommitResult.Applied(Unit),
            service(empty, emptyBlob).sweepPendingDeletedAttachments(LocalCommitGate.capture(UID), testIdentity(UID)),
            "an empty listing stopped being a no-op success",
        )

        val skipped = FakeRpcClient().withPending(ROW_A to ATT_A, ROW_B to ATT_B)
        val skippedBlob = RecordingBlobTransport()
        assertEquals(
            LocalCommitResult.Applied(Unit),
            service(skipped, skippedBlob).sweepPendingDeletedAttachments(
                commitToken = LocalCommitGate.capture(UID),
                metadataIdentity = testIdentity(UID),
                skipNoteId = { true },
            ),
            "an all-skipped sweep stopped being a no-op success",
        )
        assertTrue(skippedBlob.deletes.isEmpty(), "a skipped record was deleted")
        assertTrue(
            emptyBlob.deletes.isEmpty() && skippedBlob.deletes.isEmpty(),
            "a no-op path requested a blob deletion it never carried out",
        )

        // F-11/R22: a listing failure is not a no-op success any more — it is a failure the caller has
        // to see. What this half pins is unchanged and now strictly stronger: nothing was requested.
        val failed = FakeRpcClient().withPending(ROW_A to ATT_A)
        failed.listFailure = IllegalStateException("listing failed")
        val failedBlob = RecordingBlobTransport()
        val failure = runCatching {
            service(failed, failedBlob).sweepPendingDeletedAttachments(
                LocalCommitGate.capture(UID),
                testIdentity(UID),
            )
        }
        assertTrue(
            failure.exceptionOrNull() is IllegalStateException,
            "a listing failure was reported as ${failure.getOrNull()} instead of failing",
        )
        assertTrue(failedBlob.deletes.isEmpty(), "a failed listing still reached a record")
        assertEquals(0, failed.purges.size, "a failed listing still reached a purge")
    }

    // ---- F6-10 / F6-16: the authority the sweep uses, and where it comes from ----

    @Test
    fun `F6-10 the sweep reuses the originating token and mints none`() {
        val source = serviceSource()
        val body = sweepOwnedSource(source)
        assertTrue(body.isNotEmpty(), "precondition: the sweep's code was located")

        assertFalse(body.contains("LocalCommitGate.capture("), "the sweep minted a token of its own")
        assertFalse(body.contains("captureCoherent"), "the sweep re-read the dataset for its own token")
        assertFalse(body.contains("LocalCommitTokenProvider"), "the sweep resolved a provider instead of its token")

        val grants = Regex("""authorizeRemoteMutationStart\(""").findAll(body).count()
        val fromOriginating = Regex("""authorizeRemoteMutationStart\(\s*commitToken,""").findAll(body).count()
        assertEquals(2, grants, "the sweep's grant sites changed — recount before trusting this lane")
        assertEquals(
            grants,
            fromOriginating,
            "a sweep authorization was taken from something other than the originating token",
        )
        val signature = methodSignature(source, SWEEP)
        assertTrue(
            signature.contains("commitToken: LocalCommitToken,"),
            "the sweep's originating token went back to being optional: $signature",
        )
    }

    @Test
    fun `F6-16 every sweep remote mutation is a granted invocation`() {
        val source = serviceSource()
        val body = sweepOwnedSource(source)

        assertEquals(
            1,
            Regex("""blobTransport\.delete\(""").findAll(body).count(),
            "the sweep's blob-delete site count moved — recount before trusting this lane",
        )
        assertEquals(
            1,
            Regex("""metadataClient\.purgeDeleted\(""").findAll(body).count(),
            "the sweep's purge site count moved — recount before trusting this lane",
        )
        assertEquals(
            1,
            Regex("""consumeOnce \{ blobTransport\.delete\(""").findAll(body).count(),
            "a sweep blob deletion is reachable without consuming an authorization",
        )
        assertEquals(
            1,
            Regex("""consumeOnce \{ metadataClient\.purgeDeleted\(""").findAll(body).count(),
            "the sweep's purge is reachable without an authorization of its own",
        )
        // Scope check: the dead tokenless removal's bare deletions are *not* the sweep's, so F-6 stays
        // separately identifiable from that legacy debt.
        assertFalse(body.contains("deleteAttachmentsForNote"), "the legacy removal leaked into the sweep's code")
        assertEquals(
            4,
            Regex("""blobTransport\.delete\(""").findAll(source).count(),
            "the service's total blob-delete sites changed — the dead API's sites must stay accounted for",
        )
        // Nothing account-owned is written by the sweep at all, so the stale continuation has no local
        // mutation to fence (the surrounding `validateGeneration` calls remain its temporal contract).
        listOf("noteDao.", "syncStateStore.", "stateStore.", "staging.", "cache.", "localStorage.")
            .forEach { forbidden ->
                assertFalse(body.contains(forbidden), "the sweep gained a local mutation: $forbidden")
            }
    }

    // ---- F6-11 / F6-12: the requests are outside the gate ----

    @Test
    fun `F6-11 the blob deletion runs outside the gate and isolation can complete behind it`() =
        runTest(timeout = TIMEOUT) {
            val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
            val parked = ParkGate()
            val blob = RecordingBlobTransport(parkOnDelete = parked)
            val token = LocalCommitGate.capture(UID)

            val sweep = async { service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)) }
            parked.awaitParked()

            assertFalse(
                LocalCommitGate.mutex.isLocked,
                "the blob deletion is in flight with the gate held, so an account boundary could not proceed",
            )
            LocalCommitGate.isolate { }   // completes while the request is parked
            parked.release()

            assertEquals(LocalCommitResult.StaleGeneration, sweep.await(), "the boundary was not observed")
            assertEquals(listOf(ROW_A to ATT_A), blob.deletes.toList(), "the authorized deletion must stand")
        }

    @Test
    fun `F6-12 the purge RPC runs outside the gate and isolation can complete behind it`() =
        runTest(timeout = TIMEOUT) {
            val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
            val parked = ParkGate()
            rpc.parkOnPurge = parked
            val blob = RecordingBlobTransport()
            val token = LocalCommitGate.capture(UID)

            val sweep = async { service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)) }
            parked.awaitParked()

            assertFalse(
                LocalCommitGate.mutex.isLocked,
                "the purge RPC is in flight with the gate held, so an account boundary could not proceed",
            )
            LocalCommitGate.isolate { }
            parked.release()

            assertEquals(LocalCommitResult.StaleGeneration, sweep.await(), "the boundary was not observed")
            assertEquals(listOf(ATT_A to ROW_A), rpc.purges.toList(), "the authorized purge must stand")
        }

    // ---- F6-13: an unfinished isolation authorizes nothing ----

    @Test
    fun `F6-13 an incomplete isolation authorizes no sweep mutation`() = runTest(timeout = TIMEOUT) {
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
        val parked = ParkGate()
        val blob = RecordingBlobTransport(parkOnCaptureContext = parked)
        val service = service(rpc, blob)
        val authority = DatasetEpochAuthority(InMemoryDatasetEpochStore())
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        val token = LocalCommitGate.capture(UID)

        val sweep = async { service.sweepPendingDeletedAttachments(token, testIdentity(UID)) }
        parked.awaitParked()

        // The durable transition marker only: R15.3's incomplete state, with the gate generation where
        // it was. The sweep must come out of it with no authority at all.
        authority.beginIsolation()
        assertTrue(authority.isIsolationIncomplete(), "precondition: the isolation is unfinished")
        assertEquals(
            LocalCommitGate.capture(UID).generation,
            token.generation,
            "precondition: the unfinished transition has not moved the gate's generation",
        )
        parked.release()

        assertEquals(
            LocalCommitResult.StaleGeneration,
            sweep.await(),
            "a sweep obtained authority while the device was between datasets",
        )
        assertEquals(emptyList(), blob.deletes.toList(), "a blob deletion was issued between datasets")
        assertEquals(emptyList(), rpc.purges.toList(), "a metadata purge was issued between datasets")

        // Control: the quarantine, not the generation, is what refused — with it uninstalled the very
        // same token sweeps normally, so this lane proves the R15.3 interaction rather than a moved
        // generation.
        LocalCommitGate.installDatasetAuthorityQuarantine(null)
        val control = service(rpc, RecordingBlobTransport())
        assertEquals(
            LocalCommitResult.Applied(Unit),
            control.sweepPendingDeletedAttachments(token, testIdentity(UID)),
            "the incomplete-isolation refusal did not come from the quarantine",
        )
        assertTrue(
            authority.isIsolationIncomplete(),
            "precondition: the transition stayed unfinished, so no authority ever came from a completed one",
        )
    }

    // ---- F6-17 / F6-18: the quarantine alone, with no moved generation ----

    /**
     * F6-13's twin for the sweep's *later* mutations. The durable transition marker is persisted while
     * the first record's deletion is in flight, and the generation is asserted untouched — so nothing
     * here is a generation mismatch. Every later record's grant, and every purge, must refuse.
     *
     * This is the sweep side of R16.1's missing window. The sweep's own decisions are quarantine-aware,
     * so they refuse this state one step *before* a grant would: the sweep is covered at both levels,
     * which is why the discriminating red for the primitive lives in
     * `LocalCommitGateRemoteMutationAuthorizationTest`, in the engine lane (RMS-NSE-10) and in the
     * upload lane (AURSF-15) — paths where a suspension sits between the last decision and the next
     * grant, and the grant is therefore the only thing that can refuse.
     */
    @Test
    fun `F6-17 an unfinished isolation stops every later record`() = runTest(timeout = TIMEOUT) {
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A, ROW_B to ATT_B, ROW_C to ATT_C)
        val parked = ParkGate()
        val blob = RecordingBlobTransport(parkOnDelete = parked)
        val authority = DatasetEpochAuthority(InMemoryDatasetEpochStore())
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        val token = LocalCommitGate.capture(UID)

        val sweep = async { service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)) }
        parked.awaitParked()   // record A's deletion was granted and is on the wire

        val generation = LocalCommitGate.currentGeneration()
        authority.beginIsolation()
        assertEquals(
            generation,
            LocalCommitGate.currentGeneration(),
            "precondition: the marker alone must not move the local generation",
        )
        assertTrue(authority.isIsolationIncomplete(), "precondition: the transition is in flight")
        parked.release()

        assertEquals(
            LocalCommitResult.StaleGeneration,
            sweep.await(),
            "a sweep authorized work for a dataset the device had already left",
        )
        assertEquals(
            listOf(ROW_A to ATT_A),
            blob.deletes.toList(),
            "the already-issued deletion stands; no later record may be authorized",
        )
        assertEquals(emptyList(), rpc.purges.toList(), "a purge was authorized between datasets")
    }

    @Test
    fun `F6-18 an unfinished isolation refuses the record's purge`() = runTest(timeout = TIMEOUT) {
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
        val parked = ParkGate()
        val blob = RecordingBlobTransport(parkOnDelete = parked)
        val authority = DatasetEpochAuthority(InMemoryDatasetEpochStore())
        LocalCommitGate.installDatasetAuthorityQuarantine { authority.isIsolationIncomplete() }
        val token = LocalCommitGate.capture(UID)

        val sweep = async { service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)) }
        parked.awaitParked()   // the deletion completed its grant; the purge's is what comes next

        val generation = LocalCommitGate.currentGeneration()
        authority.beginIsolation()
        assertEquals(
            generation,
            LocalCommitGate.currentGeneration(),
            "precondition: the marker alone must not move the local generation",
        )
        parked.release()

        assertEquals(
            LocalCommitResult.StaleGeneration,
            sweep.await(),
            "a sweep whose device entered an isolation mid-record reported success",
        )
        assertEquals(listOf(ROW_A to ATT_A), blob.deletes.toList(), "precondition: the deletion happened")
        assertEquals(
            emptyList(),
            rpc.purges.toList(),
            "the purge was authorized after the device had entered an isolation",
        )
    }

    // ---- F6-14: the same uid across a boundary is still a boundary ----

    @Test
    fun `F6-14 the same uid across a boundary still blocks every sweep mutation`() = runTest(timeout = TIMEOUT) {
        val rpc = FakeRpcClient().withPending(ROW_A to ATT_A)
        val blob = RecordingBlobTransport()
        val uidBefore = currentUid
        val token = LocalCommitGate.capture(UID)

        LocalCommitGate.isolate { }

        assertEquals(
            LocalCommitResult.StaleGeneration,
            service(rpc, blob).sweepPendingDeletedAttachments(token, testIdentity(UID)),
            "a uid-only boundary was not observed, so a grant came from a replaced dataset",
        )
        assertEquals(uidBefore, currentUid, "precondition: the uid never changed")
        assertTrue(rpc.listed, "precondition: the listing ran, so this is the row's grant refusing")
        assertEquals(emptyList(), blob.deletes.toList(), "the replaced dataset deleted a blob object")
        assertEquals(emptyList(), rpc.purges.toList(), "the replaced dataset purged metadata")
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

    /** Serves the pending-deleted listing and records the metadata purges the sweep issues. */
    private class FakeRpcClient : SupabaseRpcClient {
        private val pending = mutableListOf<Pair<String, String>>()
        private var purgeFailure: String? = null

        val purges = mutableListOf<Pair<String, String>>()
        var listed = false
            private set

        /** Set to park the first purge, so a test can move the generation while it is in flight. */
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
                if (attachmentId == purgeFailure) error("purge_deleted_note_attachment failed")
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

    /**
     * The source the sweep owns: its entry point and the per-record helper that carries the two
     * mutations. Both are scanned, because R16 moved the mutation sequence into a helper without
     * moving the authority rules out of the sweep — the counts must follow the sites, not the file.
     */
    private fun sweepOwnedSource(source: String): String = SWEEP_OWNED.joinToString(separator = "\n") { name ->
        val body = methodBody(source, name)
        assertTrue(body.isNotEmpty(), "precondition: $name was located")
        body
    }

    /** Reads `AttachmentSyncService.kt` from the module this test compiles in. */
    private fun serviceSource(): String {
        val candidates = listOf(
            File("src/commonMain/kotlin/com/aus/notelikeus/data/attachments/AttachmentSyncService.kt"),
            File(
                "composeApp/src/commonMain/kotlin/com/aus/notelikeus/data/attachments/" +
                    "AttachmentSyncService.kt",
            ),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("could not read AttachmentSyncService.kt from ${candidates.joinToString { it.absolutePath }}")
        return file.readText()
    }

    /**
     * The index of the `)` that closes one suspend member's parameter list, found by paren depth
     * rather than by taking the first `)` after the name: a parameter type may itself be
     * parenthesised, and the naive search stops inside it.
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
     * The textual body of one suspend member, from its opening brace to its matching close. The
     * parameter list is skipped first, because a defaulted parameter may itself contain a brace.
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

    private companion object {
        const val SWEEP = "sweepPendingDeletedAttachments"
        val SWEEP_OWNED = listOf(SWEEP, "sweepPendingDeletedRecord")
        const val UID = "same-user"
        const val ROW_A = "101"
        const val ROW_B = "102"
        const val ROW_C = "103"
        const val ATT_A = "att-a"
        const val ATT_B = "att-b"
        const val ATT_C = "att-c"

        val TIMEOUT = 60.seconds
    }
}
