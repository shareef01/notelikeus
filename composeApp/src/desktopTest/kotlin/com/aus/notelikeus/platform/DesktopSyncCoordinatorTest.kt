package com.aus.notelikeus.platform

import com.aus.notelikeus.data.sync.awaitStableEpoch
import com.aus.notelikeus.domain.platform.PendingSyncKind
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.sync.CloudNoteTransport
import com.aus.notelikeus.data.sync.DatasetEpoch
import com.aus.notelikeus.data.sync.DatasetEpochAuthority
import com.aus.notelikeus.data.sync.DatasetPending
import com.aus.notelikeus.data.sync.FakeCloudNoteTransport
import com.aus.notelikeus.data.sync.FakeLabelDao
import com.aus.notelikeus.data.sync.FakeNoteDao
import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import com.aus.notelikeus.data.sync.IdentityBoundNoteTransport
import com.aus.notelikeus.data.sync.InMemoryDatasetEpochStore
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.PendingSyncCommand
import com.aus.notelikeus.data.sync.TestIdentityBoundNoteTransportAdapter
import com.aus.notelikeus.data.sync.testRemoteIdentityProvider
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The coordinator runs its queue on an injected scope, so these drive it with virtual time.
 *
 * Note the use of [advanceTimeBy] rather than `advanceUntilIdle()`: the coordinator's work lives
 * on `backgroundScope`, and `advanceUntilIdle()` does not push its *delayed* tasks — the debounce
 * and the backoff both silently never fire.
 *
 * The queue itself now lives in [DatasetEpochAuthority], so what these assert about persistence is
 * the store's durable state rather than an in-memory set: "the queue survived the flush" and "the
 * queue is on disk" are the same question, and the epoch-aware store is where the answer lives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSyncCoordinatorTest {

    /** Comfortably past the 2s debounce. */
    private val pastDebounce = 5_000L

    private lateinit var transport: FakeCloudNoteTransport
    private lateinit var noteDao: FakeNoteDao
    private lateinit var stateStore: FakeNoteSyncStateStore
    private lateinit var store: InMemoryDatasetEpochStore
    private lateinit var epochAuthority: DatasetEpochAuthority

    /** Flipping this to false makes every engine call fail, the way a signed-out session would. */
    private var signedIn = true

    /**
     * Wires the store, the epoch authority, the engine and the coordinator the way production does.
     *
     * The engine and the coordinator deliberately share **one** authority: two would be two
     * datasets, and a command queued through one could never be authorized by the other.
     */
    private fun wire(
        scope: CoroutineScope,
        store: InMemoryDatasetEpochStore = InMemoryDatasetEpochStore(),
        transport: (FakeCloudNoteTransport) -> IdentityBoundNoteTransport = { it },
    ): DesktopSyncCoordinator {
        this.store = store
        epochAuthority = store.authority()
        val backing = FakeCloudNoteTransport()
        this.transport = backing
        noteDao = FakeNoteDao()
        stateStore = FakeNoteSyncStateStore()

        val syncEngine = NoteSyncEngine(
            transport = transport(backing),
            // The coordinator's own session: signed out is a failure above and a refused capture here.
            remoteIdentityProvider = testRemoteIdentityProvider { if (signedIn) "uid" else null },
            noteDao = noteDao,
            labelDao = FakeLabelDao(),
            syncStateStore = stateStore,
            uidProvider = {
                if (signedIn) Result.success("uid") else Result.failure(IllegalStateException("Not signed in"))
            },
            platform = "desktop",
            datasetEpochAuthority = epochAuthority,
        )
        return DesktopSyncCoordinator(
            syncEngine = syncEngine,
            epochAuthority = epochAuthority,
            ownerUidProvider = { if (signedIn) "uid" else null },
            scope = scope,
        )
    }

    private fun DatasetPending.uploadIds(): Set<Long> =
        commandsOf(PendingSyncKind.UPLOAD).map { it.noteId }.toSet()

    /** What a previous run left on disk: one dataset, one command queued under it. */
    private fun queued(
        epoch: DatasetEpoch,
        kind: PendingSyncKind,
        vararg noteIds: Long,
        uid: String? = "uid",
    ) = DatasetPending(
        epoch = epoch,
        ownerUid = uid,
        commands = noteIds.map { PendingSyncCommand(kind, it, epoch, uid) },
    )

    private suspend fun seedNote(id: Long) {
        noteDao.insertNote(
            Note(id = id, title = "N$id", content = "", timestamp = 1L, color = 0).toNoteEntity()
        )
        stateStore.setKnownCloudIds(stateStore.knownCloudIds() + id)
        stateStore.updateKnownServerRevision(id, 1L)
    }

    @Test
    fun `coalesces a burst of edits into one flush after the debounce`() = runTest {
        signedIn = true
        val coordinator = wire(backgroundScope)
        seedNote(1L)

        repeat(5) { coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, 1L) }
        // Nothing should have gone out yet — the debounce is still running.
        advanceTimeBy(1_000)
        assertTrue(transport.notes.isEmpty(), "upload fired before the debounce elapsed")

        advanceTimeBy(pastDebounce)
        assertTrue(1L in transport.notes)
        assertTrue(store.durable.uploadIds().isEmpty(), "queue should drain on success")
    }

    @Test
    fun `a failed upload stays queued and is retried after the backoff`() = runTest {
        signedIn = false
        val coordinator = wire(backgroundScope)
        seedNote(1L)

        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, 1L)
        advanceTimeBy(pastDebounce)

        assertTrue(transport.notes.isEmpty(), "nothing should reach the cloud while signed out")
        assertEquals(setOf(1L), store.durable.uploadIds(), "a lost write is the bug being fixed")

        // The session comes back; the scheduled retry picks the note up with no new user action.
        signedIn = true
        advanceTimeBy(31_000)

        assertTrue(1L in transport.notes, "backoff retry should have flushed the queue")
        assertTrue(store.durable.uploadIds().isEmpty())
    }

    @Test
    fun `pending work from a previous run is retried on startup`() = runTest {
        signedIn = true
        val epoch = DatasetEpoch("epoch-from-previous-run")
        wire(
            backgroundScope,
            store = InMemoryDatasetEpochStore(queued(epoch, PendingSyncKind.UPLOAD, 2L)),
        )
        seedNote(2L)

        assertEquals(epoch, epochAuthority.awaitStableEpoch(), "the durable epoch was not restored")
        advanceTimeBy(pastDebounce)

        assertTrue(2L in transport.notes, "a queue restored from disk should flush itself")
    }

    @Test
    fun `a mutation during startup does not persist before the restore completes`() = runTest {
        signedIn = true
        val gated = InMemoryDatasetEpochStore(
            queued(DatasetEpoch("epoch-1"), PendingSyncKind.UPLOAD, 2L)
        )
        gated.gatedLoad = CompletableDeferred()
        val coordinator = wire(backgroundScope, store = gated)
        seedNote(1L)
        seedNote(2L)

        // The user edits note 1 while the disk restore (note 2, from a previous run) is still
        // in flight. The write triggered by the edit must wait for the restore to finish —
        // otherwise it snapshots an empty queue and the restored upload is lost forever.
        backgroundScope.launch { coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, 1L) }
        advanceTimeBy(1_000)
        assertEquals(
            setOf(2L), gated.durable.uploadIds(),
            "the enqueue must wait for the restored snapshot before writing"
        )

        gated.gatedLoad!!.complete(Unit)
        advanceTimeBy(1)

        assertEquals(
            setOf(1L, 2L), gated.durable.uploadIds(),
            "the restored note must survive alongside the new mutation"
        )

        // Both notes then flush normally.
        advanceTimeBy(pastDebounce)
        assertTrue(1L in transport.notes)
        assertTrue(2L in transport.notes)
        assertTrue(gated.durable.uploadIds().isEmpty(), "queue should drain on success")
    }

    @Test
    fun `the newest intent for a note wins`() = runTest {
        signedIn = true
        val coordinator = wire(backgroundScope)
        seedNote(3L)

        // Edited, then deleted before the debounce elapsed: the delete is what should happen.
        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, 3L)
        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.DELETE, 3L)
        advanceTimeBy(pastDebounce)

        assertTrue(3L in transport.deletedNoteIds)
        assertTrue(3L in transport.tombstones)
        assertTrue(3L !in transport.notes, "the superseded upload must not also run")
    }

    @Test
    fun `clearPending drops the queue and wipes the store on sign-out`() = runTest {
        signedIn = false
        val coordinator = wire(backgroundScope)
        seedNote(4L)

        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, 4L)
        advanceTimeBy(pastDebounce)
        assertEquals(setOf(4L), store.durable.uploadIds())

        coordinator.clearPending()
        advanceTimeBy(pastDebounce)

        assertTrue(store.durable.uploadIds().isEmpty())

        // Even signed back in, the cleared work must not resurrect.
        signedIn = true
        advanceTimeBy(120_000)
        assertTrue(transport.notes.isEmpty())
    }

    @Test
    fun `clearPending during an in-flight flush does not leave the queue on disk`() = runTest {
        signedIn = true
        val coordinator = wire(backgroundScope) { backing ->
            SlowTransport(backing, delayMs = 1_000)
        }
        for (id in 1L..4L) seedNote(id)

        for (id in 1L..4L) coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, id)
        // Into the flush but not through it: some ids are drained and still unattempted.
        advanceTimeBy(3_500)

        coordinator.clearPending()
        advanceTimeBy(120_000)

        // Cancelling the flush runs runQueue's `finally`, which puts the drained ids back. That
        // happens *after* clearPending emptied the queue, and a plain re-add would leave the
        // departed account's queue on disk to be retried at next launch.
        assertTrue(
            store.durable.uploadIds().isEmpty(),
            "a cancelled flush must not persist the queue back over a sign-out"
        )
        assertTrue(store.durable.commands.isEmpty())
    }

    @Test
    fun `clearPending during startup is not undone by the restore`() = runTest {
        signedIn = true
        val gated = InMemoryDatasetEpochStore(
            queued(DatasetEpoch("epoch-1"), PendingSyncKind.UPLOAD, 2L)
        )
        gated.gatedLoad = CompletableDeferred()
        val coordinator = wire(backgroundScope, store = gated)
        seedNote(2L)

        // Signing out while the disk restore is still in flight: the ids it is about to bring back
        // belong to the account that just left, so the restore must not resurrect them.
        backgroundScope.launch { coordinator.clearPending() }
        advanceTimeBy(1_000)
        gated.gatedLoad!!.complete(Unit)
        advanceTimeBy(120_000)

        assertTrue(gated.durable.commands.isEmpty(), "the restore repopulated a cleared queue")
        assertTrue(transport.notes.isEmpty(), "a signed-out account's queue must not flush")
    }

    /**
     * [FakeCloudNoteTransport] with one suspension point.
     *
     * Every method on the fake returns without ever yielding, so a flush over it runs start to
     * finish in a single shot and cancellation can never land mid-queue — which is precisely the
     * window the test below needs to open.
     */
    private class SlowTransport(
        private val delegate: FakeCloudNoteTransport,
        private val delayMs: Long
    ) : TestIdentityBoundNoteTransportAdapter(), CloudNoteTransport by delegate {
        override suspend fun fetchTombstones(uid: String): Map<Long, Long> {
            delay(delayMs)
            return delegate.fetchTombstones(uid)
        }

        // Must be overridden alongside fetchTombstones, not left to the delegate. `by delegate`
        // forwards this straight to the fake, whose inherited default reads the collection without
        // ever passing through the override above — so the single-note upload path would run with
        // no suspension point at all and the cancellation window below would never open.
        override suspend fun fetchTombstone(uid: String, noteId: Long): Long? {
            delay(delayMs)
            return delegate.fetchTombstone(uid, noteId)
        }
    }

    @Test
    fun `an edit cancelling a flush is not treated as a cloud failure`() = runTest {
        signedIn = true
        val coordinator = wire(backgroundScope) { SlowTransport(it, delayMs = 1_000) }
        val backing = transport
        for (id in 1L..4L) seedNote(id)

        for (id in 1L..4L) coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, id)
        // Past the debounce and into the flush, but only far enough to finish the first note.
        advanceTimeBy(3_500)

        // A save landing mid-sync cancels the running flush. NoteSyncEngine's runCatching turns
        // that cancellation into a failed Result, so the interrupted notes used to be counted as
        // cloud failures and pushed the coordinator into its 30s backoff — the user's own typing
        // delaying the sync of what they just typed. The remaining notes should go out on the
        // ordinary debounce instead.
        seedNote(5L)
        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, 5L)
        advanceTimeBy(15_000)

        for (id in 1L..5L) {
            assertTrue(id in backing.notes, "note $id should have synced without waiting on backoff")
        }
    }

    @Test
    fun `an edit during backoff does not pull the retry forward`() = runTest {
        signedIn = false
        val coordinator = wire(backgroundScope)
        seedNote(1L)
        seedNote(2L)

        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, 1L)
        advanceTimeBy(pastDebounce)
        assertEquals(setOf(1L), store.durable.uploadIds())

        // The session is back, but the coordinator is inside its 30s backoff. A save landing now
        // used to reset the failure count and reschedule at the 2s debounce, so a user editing
        // while the cloud was unreachable retried every two seconds indefinitely.
        signedIn = true
        coordinator.scheduleCurrentDatasetSync(PendingSyncKind.UPLOAD, 2L)
        advanceTimeBy(10_000)
        assertTrue(transport.notes.isEmpty(), "the retry must not be pulled forward to the debounce")

        // It still fires once the backoff actually elapses, and picks up the id queued during it.
        advanceTimeBy(25_000)
        assertTrue(1L in transport.notes)
        assertTrue(2L in transport.notes, "the note queued during backoff should ride the retry")
    }
}
