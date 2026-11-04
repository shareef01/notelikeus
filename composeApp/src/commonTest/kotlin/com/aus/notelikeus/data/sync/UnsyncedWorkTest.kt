package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.UnsyncedWork
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AUDIT-001: an ordinary sign-out clears this device's notes, so it first asks the engine what only
 * this device holds. These pin what counts, and what must not, so the warning is neither silent
 * when work would be lost nor shown when nothing would be.
 */
class UnsyncedWorkTest {

    private lateinit var transport: FakeCloudNoteTransport
    private lateinit var stateStore: FakeNoteSyncStateStore
    private lateinit var noteDao: FakeNoteDao
    private lateinit var engine: NoteSyncEngine
    private var reachable = true

    private fun setup() {
        transport = FakeCloudNoteTransport()
        stateStore = FakeNoteSyncStateStore()
        noteDao = FakeNoteDao()
        reachable = true
        engine = NoteSyncEngine(
            transport = transport,
            remoteIdentityProvider = testRemoteIdentityProvider { "uid" },
            noteDao = noteDao,
            labelDao = FakeLabelDao(),
            syncStateStore = stateStore,
            uidProvider = {
                if (reachable) Result.success("uid") else Result.failure(IllegalStateException("offline"))
            },
        )
        stateStore.setLastMergedUserId("uid")
    }

    private suspend fun local(id: Long, timestamp: Long, attachments: List<Attachment> = emptyList()) {
        noteDao.insertNote(
            Note(id = id, title = "Note $id", content = "words", timestamp = timestamp, color = 0, attachments = attachments)
                .toNoteEntity(),
        )
    }

    @Test
    fun `nothing is reported for notes that match the cloud`() = runTest {
        setup()
        local(1L, timestamp = 100L)
        stateStore.updateSyncedTimestamps(mapOf(1L to 100L))

        assertEquals(UnsyncedWork.NONE, engine.unsyncedWork())
    }

    @Test
    fun `an edit made after the last sync, and a note never synced, are both edits`() = runTest {
        setup()
        local(1L, timestamp = 200L)
        stateStore.updateSyncedTimestamps(mapOf(1L to 100L))
        local(2L, timestamp = 300L)

        assertEquals(2, engine.unsyncedWork().edits)
    }

    @Test
    fun `a delete counts while the cloud still holds the note, and not for a note it never had`() = runTest {
        setup()
        // Deleted here, cloud had it at the last download, revision known: the delete is still owed.
        stateStore.setKnownCloudIds(setOf(1L))
        stateStore.updateKnownServerRevision(1L, 7L)
        stateStore.markDeleted(1L, deletedAt = 50L, baselineRevision = 7L)
        // Deleted here before it ever reached the cloud: there is nothing to lose.
        stateStore.setDeletedDigest(2L, "digest")
        stateStore.markDeleted(2L, deletedAt = 50L, baselineRevision = null)

        assertEquals(1, engine.unsyncedWork().deletions)
    }

    @Test
    fun `a staged image counts, an uploaded one does not`() = runTest {
        setup()
        local(
            1L,
            timestamp = 100L,
            attachments = listOf(
                Attachment(id = "a", noteId = 1L, storagePath = "pending:a"),
                Attachment(id = "b", noteId = 1L, storagePath = "r2:owners/uid/notes/1/b"),
            ),
        )
        stateStore.updateSyncedTimestamps(mapOf(1L to 100L))

        assertEquals(UnsyncedWork(images = 1), engine.unsyncedWork())
    }

    @Test
    fun `the sync attempt uploads what it can before counting`() = runTest {
        setup()
        local(1L, timestamp = 200L)

        val work = engine.unsyncedWorkAfterSyncAttempt()

        assertTrue(1L in transport.notes, "the edit reached the cloud")
        assertEquals(UnsyncedWork.NONE, work)
    }

    @Test
    fun `offline, the edit is still reported after the attempt`() = runTest {
        setup()
        local(1L, timestamp = 200L)
        reachable = false

        assertEquals(1, engine.unsyncedWorkAfterSyncAttempt().edits)
    }
}
