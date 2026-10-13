package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two devices editing one note offline: whichever edit reaches the cloud second must survive.
 * Before ConflictResolution the download wrote the cloud copy over the local row and the local
 * edit was gone without a trace.
 */
class ConcurrentEditPreservationTest {

    private lateinit var transport: FakeCloudNoteTransport
    private lateinit var stateStore: FakeNoteSyncStateStore
    private lateinit var noteDao: FakeNoteDao
    private lateinit var engine: NoteSyncEngine

    private fun setup() {
        transport = FakeCloudNoteTransport()
        stateStore = FakeNoteSyncStateStore()
        noteDao = FakeNoteDao()
        engine = NoteSyncEngine(
            transport = transport,
            remoteIdentityProvider = testRemoteIdentityProvider { "uid" },
            noteDao = noteDao,
            labelDao = FakeLabelDao(),
            syncStateStore = stateStore,
            uidProvider = { Result.success("uid") },
        )
        stateStore.setLastMergedUserId("uid")
    }

    private fun cloud(content: String, clientTimestamp: Long, serverUpdatedAt: Long, revision: Long) =
        CloudNoteRecord(
            noteId = 1L, serverUpdatedAt = serverUpdatedAt, clientTimestamp = clientTimestamp,
            title = "Groceries", content = content, timestamp = clientTimestamp, color = 0,
            isPinned = false, isArchived = false, isTrashed = false,
            position = 0, reminderTimestamp = null,
            labels = emptyList(), checklistItems = emptyList(), revision = revision,
        )

    /** This device last held revision 5 (client ts 900, stamped 1000) and then edited it offline. */
    private suspend fun seedOfflineEdit() {
        noteDao.insertNote(
            Note(
                id = 1L, title = "Groceries", content = "milk, bread (typed offline)",
                timestamp = 5_000L, color = 0, serverUpdatedAt = 1_000L,
            ).toNoteEntity(),
        )
        stateStore.updateKnownServerRevision(1L, 5L)
        stateStore.updateSyncedTimestamps(mapOf(1L to 900L))
        stateStore.setKnownCloudIds(setOf(1L))
    }

    private fun contents(): List<String> = noteDao.notes.values.map { it.content }.sorted()

    @Test
    fun `a newer cloud revision keeps the offline edit as a conflicted copy`() = runTest {
        setup()
        seedOfflineEdit()
        transport.notes[1L] = cloud("milk, eggs (from phone)", 1_900L, 2_000L, 6L)

        engine.downloadAllNotes().getOrThrow()

        assertEquals(listOf("milk, bread (typed offline)", "milk, eggs (from phone)"), contents())
        assertEquals("milk, eggs (from phone)", noteDao.notes[1L]?.content)
        val copy = noteDao.notes.values.single { it.id != 1L }
        assertEquals("Groceries (conflicted copy)", copy.title)
        // Pushed back as a new note in the same run.
        assertTrue(transport.notes.values.any { it.content == "milk, bread (typed offline)" })
        assertEquals(1_900L, stateStore.syncedTimestampById()[1L])
    }

    @Test
    fun `a note another device deleted keeps the offline edit as a copy`() = runTest {
        setup()
        seedOfflineEdit()
        transport.tombstones[1L] = 2_000L

        engine.downloadAllNotes().getOrThrow()

        assertFalse(1L in noteDao.notes)
        assertEquals(listOf("milk, bread (typed offline)"), contents())
        assertEquals("Groceries (conflicted copy)", noteDao.notes.values.single().title)
    }

    @Test
    fun `a note that vanished from the cloud keeps the offline edit as a copy`() = runTest {
        setup()
        seedOfflineEdit()
        // Another note keeps the snapshot non-empty, so the vanished id reads as a real removal.
        transport.notes[2L] = cloud("other", 10L, 20L, 7L).copy(noteId = 2L)

        engine.downloadAllNotes().getOrThrow()

        assertFalse(1L in noteDao.notes)
        assertTrue(noteDao.notes.values.any { it.content == "milk, bread (typed offline)" })
    }

    @Test
    fun `an unedited stale row is simply updated, no copy`() = runTest {
        setup()
        noteDao.insertNote(
            Note(id = 1L, title = "Groceries", content = "milk", timestamp = 900L, color = 0, serverUpdatedAt = 1_000L)
                .toNoteEntity(),
        )
        stateStore.updateSyncedTimestamps(mapOf(1L to 900L))
        transport.notes[1L] = cloud("milk, eggs (from phone)", 1_900L, 2_000L, 6L)

        engine.downloadAllNotes().getOrThrow()

        assertEquals(listOf("milk, eggs (from phone)"), contents())
    }

    @Test
    fun `a pending edit on the revision the cloud still holds is pushed, not overwritten`() = runTest {
        setup()
        // The local clock runs behind: the edit's timestamp is *older* than the cloud's client
        // timestamp, which used to hand the tie to the cloud and erase the edit.
        noteDao.insertNote(
            Note(id = 1L, title = "Groceries", content = "edited", timestamp = 800L, color = 0, serverUpdatedAt = 1_000L)
                .toNoteEntity(),
        )
        stateStore.updateSyncedTimestamps(mapOf(1L to 900L))
        transport.notes[1L] = cloud("milk", 900L, 1_000L, 5L)

        engine.downloadAllNotes().getOrThrow()

        assertEquals(listOf("edited"), contents())
        assertEquals("edited", transport.notes[1L]?.content)
    }

    @Test
    fun `resolution rules`() {
        val base = Note(id = 1L, title = "T", content = "c", timestamp = 100L, color = 0, serverUpdatedAt = 200L)
        assertFalse(ConflictResolution.isLocallyEdited(base.copy(timestamp = 9_999_999L), 9_999_999L))
        assertTrue(ConflictResolution.isLocallyEdited(base.copy(timestamp = 50L), 100L))
        assertFalse(ConflictResolution.isLocallyEdited(base, null))
        assertTrue(ConflictResolution.isLocallyEdited(base.copy(timestamp = 300L), null))
        assertFalse(ConflictResolution.isLocallyEdited(base.copy(serverUpdatedAt = null), null))
        assertTrue(ConflictResolution.authoredContentEqual(base, base.copy(isPinned = true, color = 7)))
        assertEquals("Conflicted copy", ConflictResolution.conflictedCopyOf(base.copy(title = " ")).title)
        assertEquals(2000, ConflictResolution.conflictedCopyOf(base.copy(title = "x".repeat(2000))).title.length)
        assertNull(ConflictResolution.conflictedCopyOf(base).id)
    }
}
