package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.SyncCoordinator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalAccountIsolatorTest {

    /**
     * Isolating wipes the notes and the staged bytes from disk. The in-memory image cache holds
     * decrypted picture bytes and has to go with them, or a session's images outlive the session.
     * The clear existed, documented as doing exactly this, and nothing called it.
     */
    @Test
    fun `isolating drops the cached attachment bytes`() = runTest {
        var cleared = 0
        val isolator = LocalAccountIsolator(
            FakeNoteRepository(),
            FakeNoteSyncStateStore(),
            RecordingSyncCoordinator(),
            clearStagedAttachmentCache = { cleared++ },
        )

        isolator.isolate()

        assertEquals(1, cleared)
    }

    @Test
    fun `a different account signing in also drops the previous session's cached bytes`() = runTest {
        val stateStore = FakeNoteSyncStateStore()
        stateStore.setLastMergedUserId("alice")
        var cleared = 0
        val isolator = LocalAccountIsolator(
            FakeNoteRepository(),
            stateStore,
            RecordingSyncCoordinator(),
            clearStagedAttachmentCache = { cleared++ },
        )

        isolator.isolateIfAccountChanged("bob")

        assertEquals(1, cleared)
    }

    /**
     * A picture attached before signing in must survive signing in.
     *
     * Staged attachment bytes are filed under an owner namespace, so anything staged while signed
     * out sits under the guest one. `AttachmentSyncService.adoptGuestStagedAttachments` exists to
     * move them across at sign-in — and for months nothing called it, from either platform. The
     * upload path then looked only under the new account, found nothing, and left the note pointing
     * at a picture that could never arrive: a permanent broken image, no error, no request ever
     * made for it, and no amount of re-syncing would fix it.
     */
    @Test
    fun `a first sign-in adopts what the guest session staged`() = runTest {
        val adopted = mutableListOf<String>()
        val isolator = LocalAccountIsolator(
            FakeNoteRepository(),
            FakeNoteSyncStateStore(),
            RecordingSyncCoordinator(),
            adoptGuestStagedAttachments = { uid -> adopted.add(uid) },
        )

        isolator.isolateIfAccountChanged("bob")

        assertEquals(listOf("bob"), adopted)
    }

    @Test
    fun `signing in again as the same account still adopts anything left staged`() = runTest {
        val stateStore = FakeNoteSyncStateStore()
        stateStore.setLastMergedUserId("alice")
        val adopted = mutableListOf<String>()
        val isolator = LocalAccountIsolator(
            FakeNoteRepository(),
            stateStore,
            RecordingSyncCoordinator(),
            adoptGuestStagedAttachments = { uid -> adopted.add(uid) },
        )

        isolator.isolateIfAccountChanged("alice")

        assertEquals(listOf("alice"), adopted)
    }

    /**
     * The mirror case, and the one that would be a privacy bug: a different account signing in
     * wipes the device, and must not inherit the previous session's staged pictures either.
     */
    @Test
    fun `a different account does not adopt the previous session's staged bytes`() = runTest {
        val stateStore = FakeNoteSyncStateStore()
        stateStore.setLastMergedUserId("alice")
        val adopted = mutableListOf<String>()
        val isolator = LocalAccountIsolator(
            FakeNoteRepository(),
            stateStore,
            RecordingSyncCoordinator(),
            adoptGuestStagedAttachments = { uid -> adopted.add(uid) },
        )

        isolator.isolateIfAccountChanged("bob")

        assertTrue(adopted.isEmpty(), "isolating wipes the device; staged bytes must not follow")
    }

    @Test
    fun `isolate clears notes, sync state, and the pending queue`() = runTest {
        val repository = FakeNoteRepository()
        val stateStore = FakeNoteSyncStateStore()
        val coordinator = RecordingSyncCoordinator()
        repository.addNote(Note(id = 1L, title = "Alice", content = "", timestamp = 1L, color = 0))
        stateStore.setLastMergedUserId("alice")
        stateStore.setKnownCloudIds(setOf(1L))
        stateStore.markDeleted(2L, 99L)

        val isolator = LocalAccountIsolator(repository, stateStore, coordinator)
        isolator.isolate()

        assertEquals(0, repository.getCloudEligibleNoteCount())
        assertTrue(repository.clearedAll.isNotEmpty())
        assertNull(stateStore.lastMergedUserId())
        assertTrue(stateStore.knownCloudIds().isEmpty())
        assertTrue(stateStore.deletedIds().isEmpty())
        assertEquals(1, coordinator.clearPendingCount)
    }

    @Test
    fun `isolateIfAccountChanged is a no-op on a first sign-in`() = runTest {
        val repository = FakeNoteRepository()
        repository.addNote(Note(id = 1L, title = "Guest", content = "", timestamp = 1L, color = 0))
        val isolator = LocalAccountIsolator(
            repository,
            FakeNoteSyncStateStore(),
            RecordingSyncCoordinator(),
        )

        isolator.isolateIfAccountChanged("bob")

        assertEquals(1, repository.getCloudEligibleNoteCount())
        assertTrue(repository.clearedAll.isEmpty())
    }

    @Test
    fun `isolateIfAccountChanged is a no-op when the same account signs in again`() = runTest {
        val repository = FakeNoteRepository()
        val stateStore = FakeNoteSyncStateStore()
        repository.addNote(Note(id = 1L, title = "Mine", content = "", timestamp = 1L, color = 0))
        stateStore.setLastMergedUserId("alice")
        val isolator = LocalAccountIsolator(repository, stateStore, RecordingSyncCoordinator())

        isolator.isolateIfAccountChanged("alice")

        assertEquals(1, repository.getCloudEligibleNoteCount())
        assertEquals("alice", stateStore.lastMergedUserId())
    }

    @Test
    fun `isolateIfAccountChanged wipes when a different account signs in`() = runTest {
        val repository = FakeNoteRepository()
        val stateStore = FakeNoteSyncStateStore()
        val coordinator = RecordingSyncCoordinator()
        repository.addNote(Note(id = 1L, title = "Alice", content = "", timestamp = 1L, color = 0))
        stateStore.setLastMergedUserId("alice")
        stateStore.setKnownCloudIds(setOf(1L))
        val isolator = LocalAccountIsolator(repository, stateStore, coordinator)

        isolator.isolateIfAccountChanged("bob")

        assertEquals(0, repository.getCloudEligibleNoteCount())
        assertNull(stateStore.lastMergedUserId())
        assertEquals(1, coordinator.clearPendingCount)
    }
}

private class RecordingSyncCoordinator : SyncCoordinator {
    var clearPendingCount = 0
    override fun scheduleUpload(noteId: Long) {}
    override fun scheduleDelete(noteId: Long) {}
    override fun scheduleRestore(noteId: Long) {}
    override fun clearPending() {
        clearPendingCount++
    }
}
