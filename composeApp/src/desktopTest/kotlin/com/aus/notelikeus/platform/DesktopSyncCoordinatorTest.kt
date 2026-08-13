package com.aus.notelikeus.platform

import com.aus.notelikeus.data.mapper.toNoteEntity
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
}
