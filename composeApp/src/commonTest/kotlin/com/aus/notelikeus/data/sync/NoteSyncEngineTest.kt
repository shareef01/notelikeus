package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NoteSyncEngineTest {

    private lateinit var transport: FakeCloudNoteTransport
    private lateinit var stateStore: FakeNoteSyncStateStore
    private lateinit var repository: FakeNoteRepository
    private lateinit var engine: NoteSyncEngine

    private fun setup(uid: String = "uid") {
        transport = FakeCloudNoteTransport()
        stateStore = FakeNoteSyncStateStore()
        repository = FakeNoteRepository()
        engine = NoteSyncEngine(
            transport = transport,
            noteRepository = repository,
            syncStateStore = stateStore,
            uidProvider = { Result.success(uid) }
        )
    }

    // ---- cloudWinsConflict (the single source of truth) ----

    @Test
    fun `cloudWinsConflict — newer server timestamp wins`() {
        setup()
        // Remote serverUpdatedAt = 200, local = 100 → remote wins
        assertTrue(engine.cloudWinsConflict(200, 100, null, 0))
        // Remote serverUpdatedAt = 100, local = 200 → local wins
        assertFalse(engine.cloudWinsConflict(100, 200, null, 0))
    }

    @Test
    fun `cloudWinsConflict — equal server timestamps, newer client timestamp wins`() {
        setup()
        // Same server timestamp, remote client newer → remote wins
        assertTrue(engine.cloudWinsConflict(100, 100, 500, 100))
        // Same server timestamp, local client newer → local wins
        assertFalse(engine.cloudWinsConflict(100, 100, 100, 500))
    }

    @Test
    fun `cloudWinsConflict — full tie, cloud wins to avoid redundant write`() {
        setup()
        assertTrue(engine.cloudWinsConflict(100, 100, 100, 100))
    }

    @Test
    fun `cloudWinsConflict — null remote never wins`() {
        setup()
        // null remoteServerUpdatedAt with null remoteClientTimestamp → local wins
        assertFalse(engine.cloudWinsConflict(null, 100, null, 100))
        // null remoteServerUpdatedAt, remoteClientTimestamp newer → remote wins
        assertTrue(engine.cloudWinsConflict(null, null, 500, 100))
    }

    @Test
    fun `cloudWinsConflict — only local has server timestamp, remote client newer wins`() {
        setup()
        // remote has no serverUpdatedAt but newer client timestamp
        assertTrue(engine.cloudWinsConflict(null, 100, 500, 100))
    }

    // ---- uploadAllNotes ----

    @Test
    fun `uploadAllNotes fails when uid provider fails`() = runTest {
        engine = NoteSyncEngine(
            transport = FakeCloudNoteTransport(),
            noteRepository = FakeNoteRepository(),
            syncStateStore = FakeNoteSyncStateStore(),
            uidProvider = { Result.failure(IllegalStateException("no user")) }
        )
        val result = engine.uploadAllNotes()
        assertTrue(result.isFailure)
    }

    @Test
    fun `uploadAllNotes writes eligible notes to cloud`() = runTest {
        setup()
        repository.addNote(Note(id = 1L, title = "First", content = "", timestamp = 1L, color = 0))
        repository.addNote(Note(id = 2L, title = "Second", content = "", timestamp = 2L, color = 0))

        val result = engine.uploadAllNotes()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull())
        assertEquals(2, transport.notes.size)
        assertTrue(transport.syncMetaCalls.isNotEmpty())
    }

    @Test
    fun `uploadAllNotes skips notes already deleted on another device`() = runTest {
        setup()
        stateStore.markDeleted(1L, 50L)
        repository.addNote(Note(id = 1L, title = "First", content = "", timestamp = 1L, color = 0))
        repository.addNote(Note(id = 2L, title = "Second", content = "", timestamp = 2L, color = 0))

        val result = engine.uploadAllNotes()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()) // only note 2 uploaded
        assertEquals(1, transport.notes.size)
        assertTrue(1L !in transport.notes)
        assertTrue(2L in transport.notes)
    }

    // ---- reconcileUploads account-switch guard ----

    @Test
    fun `reconcileUploads skips when lastMergedUserId does not match current uid`() = runTest {
        setup(uid = "new-uid")
        stateStore.setLastMergedUserId("old-uid")
        repository.addNote(Note(id = 5L, title = "Local", content = "", timestamp = 200L, color = 0))
        stateStore.markReconciled(0L)

        val result = engine.reconcileUploads()

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
        assertTrue(transport.notes.isEmpty())
    }

    @Test
    fun `reconcileUploads pushes only notes changed since last reconcile`() = runTest {
        setup()
        stateStore.setLastMergedUserId("uid")
        stateStore.markReconciled(100L)
        val changed = Note(id = 1L, title = "New", content = "", timestamp = 200L, color = 0)
        val unchanged = Note(id = 2L, title = "Old", content = "", timestamp = 50L, color = 0)
        repository.addNote(changed)
        repository.addNote(unchanged)
        transport.nextServerTimestamp = 999L

        val result = engine.reconcileUploads()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
        // Only the changed note should be uploaded
        assertEquals("New", transport.notes[1L]?.title)
        assertTrue(2L !in transport.notes)
    }

    // ---- uploadNote ----

    @Test
    fun `uploadNote refreshes serverUpdatedAt after successful upload`() = runTest {
        setup()
        repository.addNote(Note(id = 7L, title = "Edited", content = "", timestamp = 1L, color = 0))
        transport.nextServerTimestamp = 42_000L

        val result = engine.uploadNote(7L)

        assertTrue(result.isSuccess)
        assertEquals(42_000L, repository.updatedServerTimestamps[7L])
    }

    @Test
    fun `uploadNote skips write when remote serverUpdatedAt is newer`() = runTest {
        setup()
        repository.addNote(
            Note(id = 7L, title = "Stale", content = "", timestamp = 1L, color = 0, serverUpdatedAt = 10_000L)
        )
        // Remote has newer serverUpdatedAt
        transport.notes[7L] = CloudNoteRecord(
            noteId = 7L, serverUpdatedAt = 20_000L, clientTimestamp = 1L,
            title = "Stale", content = "", timestamp = 1L, color = 0,
            isPinned = false, isArchived = false, isTrashed = false,
            position = 0, reminderTimestamp = null, labels = emptyList(), checklistItems = emptyList()
        )

        val result = engine.uploadNote(7L)

        assertTrue(result.isSuccess)
        // Transport should NOT have been called to write — the remote entry is still
        // "Stale" (the pre-populated one), not "Edited locally"
        assertEquals(1, transport.notes.size)
        assertEquals("Stale", transport.notes[7L]!!.title)
    }

    @Test
    fun `uploadNote respects cloud tombstone and deletes instead of writing`() = runTest {
        setup()
        stateStore.markDeleted(11L, 99L)
        transport.tombstones[11L] = 99L

        val result = engine.uploadNote(11L)

        assertTrue(result.isSuccess)
        assertTrue(11L in transport.deletedNoteIds)
        // Should NOT have called getNoteById because tombstone triggers delete
        assertTrue(repository.insertedNotes.isEmpty())
    }

    // ---- restoreNote ----

    @Test
    fun `restoreNote clears both tombstones and re-uploads the note`() = runTest {
        setup()
        stateStore.markDeleted(11L, 99L)
        repository.addNote(Note(id = 11L, title = "Back", content = "body", timestamp = 5L, color = 0))

        val result = engine.restoreNote(11L)

        assertTrue(result.isSuccess)
        assertTrue(11L in transport.deletedTombstoneIds)
        assertTrue(11L in transport.notes)
    }

    // ---- deleteNote ----

    @Test
    fun `deleteNote creates tombstone and deletes cloud note`() = runTest {
        setup()
        stateStore.currentTime = 999L

        val result = engine.deleteNote(42L)

        assertTrue(result.isSuccess)
        assertTrue(stateStore.isDeleted(42L))
        assertTrue(42L in transport.tombstones)
        assertTrue(42L in transport.deletedNoteIds)
    }

    // ---- downloadAllNotes ----

    @Test
    fun `downloadAllNotes imports new cloud notes`() = runTest {
        setup()
        repository.addLabel(Label(id = 1L, name = "Work"))

        transport.notes[5L] = CloudNoteRecord(
            noteId = 5L, serverUpdatedAt = 200_000L, clientTimestamp = 1L,
            title = "Cloud note", content = "Hello", timestamp = 1L, color = 0,
            isPinned = false, isArchived = false, isTrashed = false,
            position = 0, reminderTimestamp = null,
            labels = listOf("Work"), checklistItems = emptyList()
        )

        val result = engine.downloadAllNotes()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!! > 0)
        assertEquals(1, repository.insertedNotes.size)
        assertEquals("Cloud note", repository.insertedNotes.first().title)
        assertEquals("Work", repository.insertedNotes.first().labels.first().name)
    }

    @Test
    fun `downloadAllNotes does not overwrite local edit when serverUpdatedAt ties and local client is newer`() = runTest {
        setup()
        repository.addNote(
            Note(id = 5L, title = "Edited locally", content = "", timestamp = 500L, color = 0, serverUpdatedAt = 50_000L)
        )
        transport.notes[5L] = CloudNoteRecord(
            noteId = 5L, serverUpdatedAt = 50_000L, clientTimestamp = 100L,
            title = "Stale cloud", content = "", timestamp = 100L, color = 0,
            isPinned = false, isArchived = false, isTrashed = false,
            position = 0, reminderTimestamp = null, labels = emptyList(), checklistItems = emptyList()
        )

        val result = engine.downloadAllNotes()

        assertTrue(result.isSuccess)
        // Local must survive and be pushed to cloud
        assertTrue(5L in transport.notes)
        assertEquals("Edited locally", transport.notes[5L]!!.title)
    }

    @Test
    fun `downloadAllNotes accepts cloud copy when serverUpdatedAt is newer`() = runTest {
        setup()
        repository.addNote(
            Note(id = 5L, title = "Local", content = "", timestamp = 999_999L, color = 0, serverUpdatedAt = 10L)
        )
        transport.notes[5L] = CloudNoteRecord(
            noteId = 5L, serverUpdatedAt = 200_000L, clientTimestamp = 1L,
            title = "Server-confirmed", content = "", timestamp = 1L, color = 0,
            isPinned = false, isArchived = false, isTrashed = false,
            position = 0, reminderTimestamp = null, labels = emptyList(), checklistItems = emptyList()
        )

        val result = engine.downloadAllNotes()

        assertTrue(result.isSuccess)
        assertEquals("Server-confirmed", repository.updatedNotes.first().title)
    }

    // ---- deleteAllCloudData ----

    @Test
    fun `deleteAllCloudData clears notes and tombstones from cloud`() = runTest {
        setup()
        transport.notes[1L] = CloudNoteRecord(
            noteId = 1L, serverUpdatedAt = null, clientTimestamp = 1L,
            title = "x", content = "", timestamp = 1L, color = 0,
            isPinned = false, isArchived = false, isTrashed = false,
            position = 0, reminderTimestamp = null, labels = emptyList(), checklistItems = emptyList()
        )
        transport.tombstones[1L] = 1L

        val result = engine.deleteAllCloudData()

        assertTrue(result.isSuccess)
        assertTrue(1L in transport.deletedNoteIds)
        assertTrue(1L in transport.deletedTombstoneIds)
        assertTrue(transport.deleteSyncMetaCalled)
        // Local sync state must be wiped too
        assertTrue(stateStore.deletedIds().isEmpty())
    }

    @Test
    fun `deleteAllCloudData fails when uid provider fails`() = runTest {
        engine = NoteSyncEngine(
            transport = FakeCloudNoteTransport(),
            noteRepository = FakeNoteRepository(),
            syncStateStore = FakeNoteSyncStateStore(),
            uidProvider = { Result.failure(IllegalStateException("no user")) }
        )
        val result = engine.deleteAllCloudData()
        assertTrue(result.isFailure)
    }

    @Test
    fun `sync after failed restore finishes cleanup instead of re-deleting the note`() = runTest {
        setup()
        // Simulate a restore that left the cloud tombstone in place and the marker set
        stateStore.markRestored(11L)
        transport.tombstones[11L] = 500L

        val result = engine.uploadAllNotes()

        assertTrue(result.isSuccess)
        // The stale tombstone must be deleted, not merged back
        assertTrue(11L in transport.deletedTombstoneIds)
    }
}
