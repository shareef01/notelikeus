package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.SyncCoordinator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalAccountIsolatorTest {

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
