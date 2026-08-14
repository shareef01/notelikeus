package com.aus.notelikeus.platform

import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.sync.CloudNoteTransport
import com.aus.notelikeus.data.sync.FakeCloudNoteTransport
import com.aus.notelikeus.data.sync.FakeLabelDao
import com.aus.notelikeus.data.sync.FakeNoteDao
import com.aus.notelikeus.data.sync.FakeNoteSyncStateStore
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.di.DesktopPendingSyncStore
import com.aus.notelikeus.di.PendingSyncStore
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSyncCoordinatorTest {

    /** Comfortably past the 2s debounce. */
    private val pastDebounce = 5_000L

    private class FakePendingSyncStore(
        var initial: DesktopPendingSyncStore.Pending = DesktopPendingSyncStore.Pending(
            emptySet(), emptySet(), emptySet()
        )
    ) : PendingSyncStore {
        var saved: DesktopPendingSyncStore.Pending = initial
        var clearCount = 0

        override suspend fun load() = initial

        override suspend fun save(uploads: Set<Long>, deletes: Set<Long>, restores: Set<Long>) {
            saved = DesktopPendingSyncStore.Pending(uploads, deletes, restores)
        }

        override suspend fun clear() {
            clearCount++
            saved = DesktopPendingSyncStore.Pending(emptySet(), emptySet(), emptySet())
        }
    }

    /**
     * A store whose `load()` blocks until [releaseLoad] — modelling the slow disk read the
     * coordinator must not race: a mutation landing while the restore is in flight must not
     * persist a snapshot that drops the pending work the restore was about to bring back.
     */
    private class GatedPendingSyncStore(
        val initial: DesktopPendingSyncStore.Pending
    ) : PendingSyncStore {
        private val loadGate = CompletableDeferred<Unit>()
        var saved: DesktopPendingSyncStore.Pending =
            DesktopPendingSyncStore.Pending(emptySet(), emptySet(), emptySet())

        override suspend fun load(): DesktopPendingSyncStore.Pending {
            loadGate.await()
            return initial
        }

        override suspend fun save(uploads: Set<Long>, deletes: Set<Long>, restores: Set<Long>) {
            saved = DesktopPendingSyncStore.Pending(uploads, deletes, restores)
        }

        override suspend fun clear() {
            saved = DesktopPendingSyncStore.Pending(emptySet(), emptySet(), emptySet())
        }

        fun releaseLoad() {
            loadGate.complete(Unit)
        }
    }

    private lateinit var transport: FakeCloudNoteTransport
    private lateinit var noteDao: FakeNoteDao
    private lateinit var pendingStore: FakePendingSyncStore

    /** Flipping this to false makes every engine call fail, the way a signed-out session would. */
    private var signedIn = true

    private fun engine(): NoteSyncEngine {
        transport = FakeCloudNoteTransport()
        noteDao = FakeNoteDao()
        return NoteSyncEngine(
            transport = transport,
            noteDao = noteDao,
            labelDao = FakeLabelDao(),
            syncStateStore = FakeNoteSyncStateStore(),
            uidProvider = {
                if (signedIn) Result.success("uid") else Result.failure(IllegalStateException("Not signed in"))
            },
            platform = "desktop"
        )
    }

    private suspend fun seedNote(id: Long) {
        noteDao.insertNote(
            Note(id = id, title = "N$id", content = "", timestamp = 1L, color = 0).toNoteEntity()
        )
    }

    @Test
    fun `coalesces a burst of edits into one flush after the debounce`() = runTest {
        signedIn = true
        val syncEngine = engine()
        pendingStore = FakePendingSyncStore()
        val coordinator = DesktopSyncCoordinator(syncEngine, pendingStore, backgroundScope)
        seedNote(1L)

        repeat(5) { coordinator.scheduleUpload(1L) }
        // Nothing should have gone out yet — the debounce is still running.
        advanceTimeBy(1_000)
        assertTrue(transport.notes.isEmpty(), "upload fired before the debounce elapsed")

        advanceTimeBy(pastDebounce)
        assertTrue(1L in transport.notes)
        assertTrue(pendingStore.saved.uploads.isEmpty(), "queue should drain on success")
    }

    @Test
    fun `a failed upload stays queued and is retried after the backoff`() = runTest {
        signedIn = false
        val syncEngine = engine()
        pendingStore = FakePendingSyncStore()
        val coordinator = DesktopSyncCoordinator(syncEngine, pendingStore, backgroundScope)
        seedNote(1L)

        coordinator.scheduleUpload(1L)
        advanceTimeBy(pastDebounce)

        assertTrue(transport.notes.isEmpty(), "nothing should reach the cloud while signed out")
        assertEquals(setOf(1L), pendingStore.saved.uploads, "a lost write is the bug being fixed")

        // The session comes back; the scheduled retry picks the note up with no new user action.
        signedIn = true
        advanceTimeBy(31_000)

        assertTrue(1L in transport.notes, "backoff retry should have flushed the queue")
        assertTrue(pendingStore.saved.uploads.isEmpty())
    }

    @Test
    fun `pending work from a previous run is retried on startup`() = runTest {
        signedIn = true
        val syncEngine = engine()
        pendingStore = FakePendingSyncStore(
            DesktopPendingSyncStore.Pending(uploads = setOf(2L), deletes = emptySet(), restores = emptySet())
        )
        seedNote(2L)

        DesktopSyncCoordinator(syncEngine, pendingStore, backgroundScope)
        advanceTimeBy(pastDebounce)

        assertTrue(2L in transport.notes, "a queue restored from disk should flush itself")
    }

    @Test
    fun `a mutation during startup does not persist before the restore completes`() = runTest {
        signedIn = true
        val syncEngine = engine()
        val gatedStore = GatedPendingSyncStore(
            DesktopPendingSyncStore.Pending(uploads = setOf(2L), deletes = emptySet(), restores = emptySet())
        )
        val coordinator = DesktopSyncCoordinator(syncEngine, gatedStore, backgroundScope)
        seedNote(1L)
        seedNote(2L)

        // The user edits note 1 while the disk restore (note 2, from a previous run) is still
        // in flight. The persist triggered by the edit must wait for the restore to finish —
        // otherwise it snapshots an empty queue and the restored upload is lost forever.
        coordinator.scheduleUpload(1L)
        advanceTimeBy(1_000)
        assertEquals(
            emptySet(), gatedStore.saved.uploads,
            "persist must wait for the restored snapshot before writing"
        )

        gatedStore.releaseLoad()
        advanceTimeBy(1)

        assertEquals(
            setOf(2L, 1L), gatedStore.saved.uploads,
            "the restored note must survive alongside the new mutation"
        )

        // Both notes then flush normally.
        advanceTimeBy(pastDebounce)
        assertTrue(1L in transport.notes)
        assertTrue(2L in transport.notes)
        assertTrue(gatedStore.saved.uploads.isEmpty(), "queue should drain on success")
    }

    @Test
    fun `the newest intent for a note wins`() = runTest {
        signedIn = true
        val syncEngine = engine()
        pendingStore = FakePendingSyncStore()
        val coordinator = DesktopSyncCoordinator(syncEngine, pendingStore, backgroundScope)
        seedNote(3L)

        // Edited, then deleted before the debounce elapsed: the delete is what should happen.
        coordinator.scheduleUpload(3L)
        coordinator.scheduleDelete(3L)
        advanceTimeBy(pastDebounce)

        assertTrue(3L in transport.deletedNoteIds)
        assertTrue(3L in transport.tombstones)
        assertTrue(3L !in transport.notes, "the superseded upload must not also run")
    }

    @Test
    fun `clearPending drops the queue and wipes the store on sign-out`() = runTest {
        signedIn = false
        val syncEngine = engine()
        pendingStore = FakePendingSyncStore()
        val coordinator = DesktopSyncCoordinator(syncEngine, pendingStore, backgroundScope)
        seedNote(4L)

        coordinator.scheduleUpload(4L)
        advanceTimeBy(pastDebounce)
        assertEquals(setOf(4L), pendingStore.saved.uploads)

        coordinator.clearPending()
        advanceTimeBy(pastDebounce)

        assertTrue(pendingStore.clearCount > 0, "clearPending used to be a no-op")
        assertTrue(pendingStore.saved.uploads.isEmpty())

        // Even signed back in, the cleared work must not resurrect.
        signedIn = true
        advanceTimeBy(120_000)
        assertTrue(transport.notes.isEmpty())
    }

    @Test
    fun `clearPending during an in-flight flush does not leave the queue on disk`() = runTest {
        signedIn = true
        val backing = FakeCloudNoteTransport()
        noteDao = FakeNoteDao()
        val syncEngine = NoteSyncEngine(
            transport = SlowTransport(backing, delayMs = 1_000),
            noteDao = noteDao,
            labelDao = FakeLabelDao(),
            syncStateStore = FakeNoteSyncStateStore(),
            uidProvider = { Result.success("uid") },
            platform = "desktop"
        )
        pendingStore = FakePendingSyncStore()
        val coordinator = DesktopSyncCoordinator(syncEngine, pendingStore, backgroundScope)
        for (id in 1L..4L) seedNote(id)

        for (id in 1L..4L) coordinator.scheduleUpload(id)
        // Into the flush but not through it: some ids are drained and still unattempted.
        advanceTimeBy(3_500)

        coordinator.clearPending()
        advanceTimeBy(120_000)

        // Cancelling the flush runs runQueue's `finally`, which puts the drained ids back, and
        // flush's `finally`, which persists them. Both happen *after* clearPending emptied the
        // queue, and the two writes race on an unordered scope — so the sign-out could be
        // overwritten and the departed account's queue left on disk to retry at next launch.
        assertTrue(
            pendingStore.saved.uploads.isEmpty(),
            "a cancelled flush must not persist the queue back over a sign-out"
        )
        assertTrue(
            pendingStore.saved.deletes.isEmpty() && pendingStore.saved.restores.isEmpty()
        )
    }

    @Test
    fun `clearPending during startup is not undone by the restore`() = runTest {
        signedIn = true
        val syncEngine = engine()
        val gatedStore = GatedPendingSyncStore(
            DesktopPendingSyncStore.Pending(uploads = setOf(2L), deletes = emptySet(), restores = emptySet())
        )
        val coordinator = DesktopSyncCoordinator(syncEngine, gatedStore, backgroundScope)
        seedNote(2L)

        // Signing out while the disk restore is still in flight: the ids it is about to bring back
        // belong to the account that just left, so the restore must not resurrect them.
        coordinator.clearPending()
        gatedStore.releaseLoad()
        advanceTimeBy(120_000)

        assertTrue(gatedStore.saved.uploads.isEmpty(), "the restore repopulated a cleared queue")
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
    ) : CloudNoteTransport by delegate {
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
        val backing = FakeCloudNoteTransport()
        noteDao = FakeNoteDao()
        val syncEngine = NoteSyncEngine(
            transport = SlowTransport(backing, delayMs = 1_000),
            noteDao = noteDao,
            labelDao = FakeLabelDao(),
            syncStateStore = FakeNoteSyncStateStore(),
            uidProvider = { Result.success("uid") },
            platform = "desktop"
        )
        pendingStore = FakePendingSyncStore()
        val coordinator = DesktopSyncCoordinator(syncEngine, pendingStore, backgroundScope)
        for (id in 1L..4L) seedNote(id)

        for (id in 1L..4L) coordinator.scheduleUpload(id)
        // Past the debounce and into the flush, but only far enough to finish the first note.
        advanceTimeBy(3_500)

        // A save landing mid-sync cancels the running flush. NoteSyncEngine's runCatching turns
        // that cancellation into a failed Result, so the interrupted notes used to be counted as
        // cloud failures and pushed the coordinator into its 30s backoff — the user's own typing
        // delaying the sync of what they just typed. The remaining notes should go out on the
        // ordinary debounce instead.
        seedNote(5L)
        coordinator.scheduleUpload(5L)
        advanceTimeBy(15_000)

        for (id in 1L..5L) {
            assertTrue(id in backing.notes, "note $id should have synced without waiting on backoff")
        }
    }

    @Test
    fun `an edit during backoff does not pull the retry forward`() = runTest {
        signedIn = false
        val syncEngine = engine()
        pendingStore = FakePendingSyncStore()
        val coordinator = DesktopSyncCoordinator(syncEngine, pendingStore, backgroundScope)
        seedNote(1L)
        seedNote(2L)

        coordinator.scheduleUpload(1L)
        advanceTimeBy(pastDebounce)
        assertEquals(setOf(1L), pendingStore.saved.uploads)

        // The session is back, but the coordinator is inside its 30s backoff. A save landing now
        // used to reset the failure count and reschedule at the 2s debounce, so a user editing
        // while the cloud was unreachable retried every two seconds indefinitely.
        signedIn = true
        coordinator.scheduleUpload(2L)
        advanceTimeBy(10_000)
        assertTrue(transport.notes.isEmpty(), "the retry must not be pulled forward to the debounce")

        // It still fires once the backoff actually elapses, and picks up the id queued during it.
        advanceTimeBy(25_000)
        assertTrue(1L in transport.notes)
        assertTrue(2L in transport.notes, "the note queued during backoff should ride the retry")
    }
}
