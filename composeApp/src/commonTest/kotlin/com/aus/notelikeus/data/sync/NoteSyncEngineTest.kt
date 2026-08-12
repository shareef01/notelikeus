package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.mapper.toNote
import com.aus.notelikeus.data.mapper.toNoteEntity
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
    private lateinit var noteDao: FakeNoteDao
    private lateinit var labelDao: FakeLabelDao
    private lateinit var engine: NoteSyncEngine

    private fun setup(uid: String = "uid") {
        transport = FakeCloudNoteTransport()
        stateStore = FakeNoteSyncStateStore()
        noteDao = FakeNoteDao()
        labelDao = FakeLabelDao()
        engine = NoteSyncEngine(
            transport = transport,
            noteDao = noteDao,
            labelDao = labelDao,
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
            noteDao = FakeNoteDao(),
            labelDao = FakeLabelDao(),
            syncStateStore = FakeNoteSyncStateStore(),
            uidProvider = { Result.failure(IllegalStateException("no user")) }
        )
        val result = engine.uploadAllNotes()
        assertTrue(result.isFailure)
    }

    @Test
    fun `uploadAllNotes writes eligible notes to cloud`() = runTest {
        setup()
        noteDao.insertNote(Note(id = 1L, title = "First", content = "", timestamp = 1L, color = 0).toNoteEntity())
        noteDao.insertNote(Note(id = 2L, title = "Second", content = "", timestamp = 2L, color = 0).toNoteEntity())

        val result = engine.uploadAllNotes()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull())
        assertEquals(2, transport.notes.size)
    }

    @Test
    fun `uploadAllNotes skips notes already deleted on another device`() = runTest {
        setup()
        stateStore.markDeleted(1L, 50L)
        noteDao.insertNote(Note(id = 1L, title = "First", content = "", timestamp = 1L, color = 0).toNoteEntity())
        noteDao.insertNote(Note(id = 2L, title = "Second", content = "", timestamp = 2L, color = 0).toNoteEntity())

        val result = engine.uploadAllNotes()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()) // only note 2 uploaded
        assertEquals(1, transport.notes.size)
        assertTrue(1L !in transport.notes)
        assertTrue(2L in transport.notes)
    }

    // ---- uploadAllNotes: guarding against a fetch that failed open ----

    @Test
    fun `uploadAllNotes refuses to overwrite the cloud when it comes back unexpectedly empty`() = runTest {
        setup()
        // A previous sync established that note 1 lives in the cloud for this account.
        stateStore.setLastMergedUserId("uid")
        stateStore.setKnownCloudIds(setOf(1L))
        noteDao.insertNote(
            Note(id = 1L, title = "Stale local copy", content = "", timestamp = 1L, color = 0).toNoteEntity()
        )
        // The fetch now returns nothing. Without a guard every remote timestamp reads as null,
        // cloudWinsConflict answers "local wins", and this stale copy is pushed over a cloud note
        // that may well be newer.

        val result = engine.uploadAllNotes()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SuspectEmptyCloudException)
        assertTrue(transport.notes.isEmpty(), "nothing may be pushed on a suspect empty fetch")
    }

    @Test
    fun `uploadAllNotes treats an empty cloud as legitimate on a first sync`() = runTest {
        setup()
        noteDao.insertNote(
            Note(id = 1L, title = "Local only", content = "", timestamp = 1L, color = 0).toNoteEntity()
        )
        // No prior sync, so an empty cloud is exactly what a first upload should expect.

        val result = engine.uploadAllNotes()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
        assertTrue(1L in transport.notes)
    }

    @Test
    fun `uploadAllNotes ignores known cloud ids left over from another account`() = runTest {
        setup(uid = "second-account")
        stateStore.setLastMergedUserId("first-account")
        stateStore.setKnownCloudIds(setOf(1L))
        noteDao.insertNote(
            Note(id = 1L, title = "Mine", content = "", timestamp = 1L, color = 0).toNoteEntity()
        )

        val result = engine.uploadAllNotes()

        assertTrue(result.isSuccess, "an account switch is not a failed fetch")
        assertTrue(1L in transport.notes)
    }

    // ---- reconcileUploads account-switch guard ----

    @Test
    fun `reconcileUploads skips when lastMergedUserId does not match current uid`() = runTest {
        setup(uid = "new-uid")
        stateStore.setLastMergedUserId("old-uid")
        noteDao.insertNote(Note(id = 5L, title = "Local", content = "", timestamp = 200L, color = 0).toNoteEntity())
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
        noteDao.insertNote(changed.toNoteEntity())
        noteDao.insertNote(unchanged.toNoteEntity())
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
        noteDao.insertNote(Note(id = 7L, title = "Edited", content = "", timestamp = 1L, color = 0).toNoteEntity())
        transport.nextServerTimestamp = 42_000L

        val result = engine.uploadNote(7L)

        assertTrue(result.isSuccess)
        assertEquals(42_000L, noteDao.updatedServerTimestamps[7L])
    }

    @Test
    fun `uploadNote skips write when remote serverUpdatedAt is newer`() = runTest {
        setup()
        noteDao.insertNote(
            Note(id = 7L, title = "Stale", content = "", timestamp = 1L, color = 0, serverUpdatedAt = 10_000L).toNoteEntity()
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
    }

    // ---- restoreNote ----

    @Test
    fun `restoreNote clears both tombstones and re-uploads the note`() = runTest {
        setup()
        stateStore.markDeleted(11L, 99L)
        noteDao.insertNote(Note(id = 11L, title = "Back", content = "body", timestamp = 5L, color = 0).toNoteEntity())

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
        labelDao.insertLabel(com.aus.notelikeus.data.local.entity.LabelEntity(id = 1L, name = "Work"))

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
        assertEquals(1, noteDao.notes.size)
        assertEquals("Cloud note", noteDao.notes[5L]?.title)
    }

    // ---- downloadAllNotes: guarding against a fetch that failed open ----

    @Test
    fun `downloadAllNotes refuses to delete locals when the cloud comes back unexpectedly empty`() = runTest {
        setup()
        // The device has completed a download for this account before, and knows note 1 was there.
        noteDao.insertNote(
            Note(id = 1L, title = "Keep me", content = "", timestamp = 1L, color = 0).toNoteEntity()
        )
        stateStore.setLastMergedUserId("uid")
        stateStore.setKnownCloudIds(setOf(1L))
        // ...and now the cloud reports nothing at all, with no tombstone to explain it.

        val result = engine.downloadAllNotes()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SuspectEmptyCloudException)
        assertEquals(1, noteDao.notes.size, "local note must survive a suspect empty fetch")
        assertFalse(stateStore.isDeleted(1L))
        assertTrue(transport.tombstones.isEmpty(), "no tombstone should be published")
    }

    @Test
    fun `downloadAllNotes still deletes a local note the cloud genuinely dropped`() = runTest {
        setup()
        noteDao.insertNote(
            Note(id = 1L, title = "Gone", content = "", timestamp = 1L, color = 0).toNoteEntity()
        )
        noteDao.insertNote(
            Note(id = 2L, title = "Still here", content = "", timestamp = 1L, color = 0).toNoteEntity()
        )
        stateStore.setLastMergedUserId("uid")
        stateStore.setKnownCloudIds(setOf(1L, 2L))
        // The cloud kept 2 but dropped 1 — a partial result, not a failed fetch.
        transport.notes[2L] = CloudNoteRecord(
            noteId = 2L, serverUpdatedAt = 200_000L, clientTimestamp = 1L,
            title = "Still here", content = "", timestamp = 1L, color = 0,
            isPinned = false, isArchived = false, isTrashed = false,
            position = 0, reminderTimestamp = null,
            labels = emptyList(), checklistItems = emptyList()
        )

        val result = engine.downloadAllNotes()

        assertTrue(result.isSuccess)
        assertFalse(1L in noteDao.notes)
        assertTrue(2L in noteDao.notes)
    }

    @Test
    fun `downloadAllNotes treats an empty cloud as legitimate on a first sync`() = runTest {
        setup()
        noteDao.insertNote(
            Note(id = 1L, title = "Local only", content = "", timestamp = 1L, color = 0).toNoteEntity()
        )
        // No prior download: nothing is "known" to have been in the cloud.

        val result = engine.downloadAllNotes()

        assertTrue(result.isSuccess)
        assertEquals(1, noteDao.notes.size)
        assertTrue(1L in transport.notes, "the local note should be pushed up instead")
    }

    @Test
    fun `downloadAllNotes ignores known cloud ids left over from another account`() = runTest {
        setup(uid = "second-account")
        noteDao.insertNote(
            Note(id = 1L, title = "Mine", content = "", timestamp = 1L, color = 0).toNoteEntity()
        )
        // State left behind by the previously signed-in account.
        stateStore.setLastMergedUserId("first-account")
        stateStore.setKnownCloudIds(setOf(1L))

        val result = engine.downloadAllNotes()

        assertTrue(result.isSuccess, "an account switch is not a failed fetch")
        assertEquals(1, noteDao.notes.size, "the other account's ids must not delete these notes")
        assertTrue(1L in transport.notes)
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
            noteDao = FakeNoteDao(),
            labelDao = FakeLabelDao(),
            syncStateStore = FakeNoteSyncStateStore(),
            uidProvider = { Result.failure(IllegalStateException("no user")) }
        )
        val result = engine.deleteAllCloudData()
        assertTrue(result.isFailure)
    }
}
