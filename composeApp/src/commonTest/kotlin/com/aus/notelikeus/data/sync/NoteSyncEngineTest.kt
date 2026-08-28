package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.local.entity.ChecklistItemEntity
import com.aus.notelikeus.data.local.entity.LabelEntity
import com.aus.notelikeus.data.local.entity.NoteEntity
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef
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
    fun `cloudWinsConflict — a confirmed local note beats an unconfirmed remote one`() {
        setup()
        // Remote is a legacy document written before serverUpdatedAt existed; local has been
        // confirmed by the server. However far ahead the remote client clock reads, it does not
        // get to overrule a revision the server stamped.
        assertFalse(engine.cloudWinsConflict(null, 100, 500, 100))
        assertFalse(engine.cloudWinsConflict(null, 100, Long.MAX_VALUE, 0))
    }

    @Test
    fun `cloudWinsConflict — a confirmed remote note beats an unconfirmed local one`() {
        setup()
        // The mirror case, and the one an ordinary upgrade reaches: MIGRATION_5_6 adds the column
        // with no backfill, so every note predating schema v6 reads null locally until its next
        // upload. A skewed clock or a hand-edited backup timestamp must not win from there.
        assertTrue(engine.cloudWinsConflict(100, null, 0, Long.MAX_VALUE))
        assertTrue(engine.cloudWinsConflict(100, null, null, 500))
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
    fun `uploadAllNotes refuses when lastMergedUserId belongs to another account`() = runTest {
        setup(uid = "second-account")
        stateStore.setLastMergedUserId("first-account")
        stateStore.setKnownCloudIds(setOf(1L))
        noteDao.insertNote(
            Note(id = 1L, title = "Mine", content = "", timestamp = 1L, color = 0).toNoteEntity()
        )

        val result = engine.uploadAllNotes()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is WrongAccountSyncException)
        assertTrue(transport.notes.isEmpty(), "must not push leftover local notes under the new uid")
        assertTrue(transport.syncMetaCalls.isEmpty())
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

    @Test
    fun `reconcileUploads refuses to push when a known cloud note fetches back missing`() = runTest {
        setup()
        stateStore.setLastMergedUserId("uid")
        stateStore.markReconciled(100L)
        // The last full download saw note 1 in the cloud...
        stateStore.setKnownCloudIds(setOf(1L))
        noteDao.insertNote(
            Note(id = 1L, title = "Stale local copy", content = "", timestamp = 200L, color = 0).toNoteEntity()
        )
        // ...but the per-note fetch now answers "missing", with no tombstone to explain it.

        val result = engine.reconcileUploads()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SuspectEmptyCloudException)
        assertTrue(transport.notes.isEmpty(), "nothing may be pushed over a note that failed to fetch")
    }

    @Test
    fun `reconcileUploads still pushes a note that was never in the cloud`() = runTest {
        setup()
        stateStore.setLastMergedUserId("uid")
        stateStore.markReconciled(100L)
        // Nothing known to be in the cloud, so a missing remote is simply a note that has not
        // synced yet — the ordinary case, and it must not trip the guard above.
        noteDao.insertNote(
            Note(id = 1L, title = "Brand new", content = "", timestamp = 200L, color = 0).toNoteEntity()
        )

        val result = engine.reconcileUploads()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
        assertTrue(1L in transport.notes)
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

    @Test
    fun `restoreNote marks the note restored before clearing the local tombstone`() = runTest {
        setup()
        stateStore.markDeleted(11L, 99L)
        transport.tombstones[11L] = 99L
        noteDao.insertNote(
            Note(id = 11L, title = "Back", content = "body", timestamp = 5L, color = 0).toNoteEntity()
        )

        // Offline, or the token expired: the cloud tombstone delete never lands.
        transport.deleteTombstonesFailure = IllegalStateException("offline")
        assertTrue(engine.restoreNote(11L).isFailure)

        // The local tombstone is gone (it has to be, or the row the repository just re-inserted is
        // purged on the next sync) — so the only thing standing between the stranded cloud
        // tombstone and a second, silent deletion is the restore marker.
        assertFalse(stateStore.isDeleted(11L))
        assertTrue(11L in stateStore.restoredIds())
    }

    @Test
    fun `a stranded cloud tombstone does not re-delete a restored note`() = runTest {
        setup()
        stateStore.markDeleted(11L, 99L)
        transport.tombstones[11L] = 99L
        noteDao.insertNote(
            Note(id = 11L, title = "Back", content = "body", timestamp = 5L, color = 0).toNoteEntity()
        )

        transport.deleteTombstonesFailure = IllegalStateException("offline")
        assertTrue(engine.restoreNote(11L).isFailure)

        // Back online. Without the restore marker this download re-imports the cloud tombstone and
        // purgeLocalTombstonedNotes destroys the note the user just brought back.
        transport.deleteTombstonesFailure = null
        assertTrue(engine.downloadAllNotes().isSuccess)

        assertNotNull(noteDao.getNoteById(11L))
        // The marker also drives the retry: the stale cloud tombstone is cleaned up here, and only
        // then does the marker go away.
        assertTrue(11L in transport.deletedTombstoneIds)
        assertFalse(11L in stateStore.restoredIds())
    }

    // ---- reconcileUploads high-water ordering ----

    /**
     * `highWater` becomes the next run's `since`, and the filter is `timestamp > since`. An edit
     * saved after the snapshot but before the clock read would carry a timestamp inside that window
     * *and* be absent from the snapshot, so every later reconcile would skip it — the note would
     * simply never upload again.
     *
     * The property is about ordering, not about the return value, so it can only be observed by
     * watching when the engine reads the clock. Hence the injected clock and the DAO hook.
     */
    @Test
    fun `reconcile reads the clock before taking the note snapshot`() = runTest {
        transport = FakeCloudNoteTransport()
        stateStore = FakeNoteSyncStateStore()
        noteDao = FakeNoteDao()
        labelDao = FakeLabelDao()

        var tick = 0L
        val clockReadsBeforeSnapshot = mutableListOf<Long>()
        var snapshotTaken = false
        noteDao.onGetAllNotesForBackup = { snapshotTaken = true }

        engine = NoteSyncEngine(
            transport = transport,
            noteDao = noteDao,
            labelDao = labelDao,
            syncStateStore = stateStore,
            uidProvider = { Result.success("uid") },
            now = {
                tick += 1000L
                if (!snapshotTaken) clockReadsBeforeSnapshot += tick
                tick
            }
        )
        stateStore.setLastMergedUserId("uid")

        assertTrue(engine.reconcileUploads().isSuccess)

        // The high-water mark that was stored has to be one the engine read *before* it looked at
        // the notes, so the window it closes cannot contain an edit it never saw.
        assertTrue(
            clockReadsBeforeSnapshot.isNotEmpty(),
            "the clock was not read before the snapshot at all"
        )
        assertTrue(
            stateStore.lastReconciledAt() in clockReadsBeforeSnapshot,
            "markReconciled stored ${stateStore.lastReconciledAt()}, " +
                "which was not read before the snapshot ($clockReadsBeforeSnapshot)"
        )
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

    @Test
    fun `downloadAllNotes leaves an unchanged note alone and reports no changes`() = runTest {
        val baseDao = FakeNoteDao()
        var updates = 0
        setupWithDao(object : NoteDao by baseDao {
            override suspend fun updateNote(note: NoteEntity) {
                updates++
                baseDao.updateNote(note)
            }
        })

        // Steady state: the same confirmed revision on both sides. cloudWinsConflict answers
        // "cloud" here by design (the tie avoids pushing the note back up), which is exactly why
        // the branch has to check whether anything actually differs before rewriting the row.
        baseDao.insertNote(
            Note(
                id = 5L, title = "Same", content = "Body", timestamp = 1L, color = 0,
                serverUpdatedAt = 200_000L
            ).toNoteEntity()
        )
        transport.notes[5L] = cloudRecord(title = "Same", content = "Body")

        val result = engine.downloadAllNotes()

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull(), "an unchanged library is not a change")
        assertEquals(0, updates, "a row that already matches must not be rewritten")
    }

    @Test
    fun `downloadAllNotes still applies a cloud note that really differs`() = runTest {
        val baseDao = FakeNoteDao()
        var updates = 0
        setupWithDao(object : NoteDao by baseDao {
            override suspend fun updateNote(note: NoteEntity) {
                updates++
                baseDao.updateNote(note)
            }
        })

        baseDao.insertNote(
            Note(
                id = 5L, title = "Stale", content = "Old", timestamp = 1L, color = 0,
                serverUpdatedAt = 100_000L
            ).toNoteEntity()
        )
        transport.notes[5L] = cloudRecord(title = "Fresh", content = "New")

        val result = engine.downloadAllNotes()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
        assertEquals(1, updates)
        assertEquals("Fresh", baseDao.notes[5L]?.title)
    }

    private fun setupWithDao(dao: NoteDao) {
        transport = FakeCloudNoteTransport()
        stateStore = FakeNoteSyncStateStore()
        noteDao = FakeNoteDao()
        labelDao = FakeLabelDao()
        engine = NoteSyncEngine(
            transport = transport,
            noteDao = dao,
            labelDao = labelDao,
            syncStateStore = stateStore,
            uidProvider = { Result.success("uid") }
        )
    }

    private fun cloudRecord(title: String, content: String) = CloudNoteRecord(
        noteId = 5L, serverUpdatedAt = 200_000L, clientTimestamp = 1L,
        title = title, content = content, timestamp = 1L, color = 0,
        isPinned = false, isArchived = false, isTrashed = false,
        position = 0, reminderTimestamp = null,
        labels = emptyList(), checklistItems = emptyList()
    )

    // ---- runInTransaction: production DI wraps multi-statement writes atomically ----

    @Test
    fun `runInTransaction wraps each multi-statement write as one unit`() = runTest {
        transport = FakeCloudNoteTransport()
        stateStore = FakeNoteSyncStateStore()
        noteDao = FakeNoteDao()
        labelDao = FakeLabelDao()

        var transactions = 0
        var daoCallsTotal = 0
        var daoCallsInside = 0
        val baseDao = FakeNoteDao()
        val countingDao = object : NoteDao by baseDao {
            override suspend fun insertNote(note: NoteEntity): Long {
                daoCallsTotal++
                return baseDao.insertNote(note)
            }

            override suspend fun insertNoteLabelCrossRef(crossRef: NoteLabelCrossRef) {
                daoCallsTotal++
                baseDao.insertNoteLabelCrossRef(crossRef)
            }

            override suspend fun insertChecklistItem(item: ChecklistItemEntity) {
                daoCallsTotal++
                baseDao.insertChecklistItem(item)
            }
        }
        engine = NoteSyncEngine(
            transport = transport,
            noteDao = countingDao,
            labelDao = labelDao,
            syncStateStore = stateStore,
            uidProvider = { Result.success("uid") },
            runInTransaction = { block ->
                transactions++
                val before = daoCallsTotal
                block()
                daoCallsInside += daoCallsTotal - before
            }
        )

        // Two cloud notes, each carrying a label and a checklist item, so every import is
        // genuinely multi-statement — the shape a partial write would corrupt.
        labelDao.insertLabel(LabelEntity(id = 1L, name = "Work"))
        labelDao.insertLabel(LabelEntity(id = 2L, name = "Home"))
        transport.notes[1L] = CloudNoteRecord(
            noteId = 1L, serverUpdatedAt = 200_000L, clientTimestamp = 1L,
            title = "One", content = "Hello", timestamp = 1L, color = 0,
            isPinned = false, isArchived = false, isTrashed = false,
            position = 0, reminderTimestamp = null,
            labels = listOf("Work"),
            checklistItems = listOf(ChecklistItemData(text = "a", isChecked = false, position = 0))
        )
        transport.notes[2L] = CloudNoteRecord(
            noteId = 2L, serverUpdatedAt = 201_000L, clientTimestamp = 1L,
            title = "Two", content = "World", timestamp = 1L, color = 0,
            isPinned = false, isArchived = false, isTrashed = false,
            position = 0, reminderTimestamp = null,
            labels = listOf("Home"),
            checklistItems = listOf(ChecklistItemData(text = "b", isChecked = true, position = 0))
        )

        val result = engine.downloadAllNotes()

        assertTrue(result.isSuccess)
        assertEquals(2, baseDao.notes.size)
        assertEquals(2, transactions, "one transaction per imported note")
        assertTrue(daoCallsInside > 0, "the transaction wrapper must actually run its block")
        assertEquals(
            daoCallsInside, daoCallsTotal,
            "no note, label, or checklist write may escape the transaction wrapper"
        )
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
    fun `downloadAllNotes refuses when lastMergedUserId belongs to another account`() = runTest {
        setup(uid = "second-account")
        noteDao.insertNote(
            Note(id = 1L, title = "Mine", content = "", timestamp = 1L, color = 0).toNoteEntity()
        )
        stateStore.setLastMergedUserId("first-account")
        stateStore.setKnownCloudIds(setOf(1L))
        stateStore.markDeleted(1L, 100L)
        transport.notes[1L] = CloudNoteRecord(
            noteId = 1L, serverUpdatedAt = 200L, clientTimestamp = 1L,
            title = "Bob's note", content = "", timestamp = 1L, color = 0,
            isPinned = false, isArchived = false, isTrashed = false,
            position = 0, reminderTimestamp = null, labels = emptyList(), checklistItems = emptyList()
        )

        val result = engine.downloadAllNotes()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is WrongAccountSyncException)
        assertEquals(1, noteDao.notes.size, "local notes are left for the isolator, not mixed here")
        assertTrue(1L in transport.notes, "leftover tombstone must not delete the new account's cloud note")
        assertTrue(1L !in transport.deletedNoteIds)
    }

    @Test
    fun `deleteNote refuses leftover deletes for another account`() = runTest {
        setup(uid = "bob")
        stateStore.setLastMergedUserId("alice")
        transport.notes[1L] = CloudNoteRecord(
            noteId = 1L, serverUpdatedAt = 200L, clientTimestamp = 1L,
            title = "Bob's note", content = "", timestamp = 1L, color = 0,
            isPinned = false, isArchived = false, isTrashed = false,
            position = 0, reminderTimestamp = null, labels = emptyList(), checklistItems = emptyList()
        )

        val result = engine.deleteNote(1L)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is WrongAccountSyncException)
        assertFalse(stateStore.isDeleted(1L))
        assertTrue(1L in transport.notes)
        assertTrue(transport.deletedNoteIds.isEmpty())
        assertTrue(transport.tombstones.isEmpty())
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
