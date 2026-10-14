package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AU-07 on Android/Windows. A delete made before this device knew the note's revision cannot be
 * sent, and the download used to let the cloud copy win and quietly bring it back. Now the
 * repository records a fingerprint of the deleted words, and the download completes the delete at
 * the revision it observes only when the cloud copy still says exactly that.
 */
class VerifiedDeleteTest {

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

    private fun cloud(content: String) = CloudNoteRecord(
        noteId = 1L, serverUpdatedAt = 2_000L, clientTimestamp = 1_900L,
        title = "Groceries", content = content, timestamp = 1_900L, color = 0,
        isPinned = false, isArchived = false, isTrashed = false,
        position = 0, reminderTimestamp = null,
        labels = emptyList(), checklistItems = emptyList(), revision = 7L,
    )

    private val deleted = Note(id = 1L, title = "Groceries", content = "milk", timestamp = 900L, color = 0)

    /** As the repository and deleteNoteWithToken leave it: tombstone, fingerprint, no baseline. */
    private fun deletedHereWithoutRevision() {
        stateStore.setDeletedDigest(1L, ConflictResolution.authoredContentDigest(deleted))
        stateStore.markDeleted(1L, deletedAt = 50L, baselineRevision = null)
    }

    @Test
    fun `the cloud copy still holding the deleted words is deleted at the observed revision`() = runTest {
        setup()
        deletedHereWithoutRevision()
        transport.notes[1L] = cloud("milk")

        engine.downloadAllNotes().getOrThrow()

        assertEquals(listOf(7L), transport.deleteAttempts.filter { it.noteId == 1L }.map { it.baseRevision })
        assertFalse(1L in noteDao.notes)
        assertFalse(1L in transport.notes)
        assertNull(stateStore.deletedDigestById()[1L])
    }

    @Test
    fun `a cloud copy changed since the delete wins and is restored`() = runTest {
        setup()
        deletedHereWithoutRevision()
        transport.notes[1L] = cloud("milk, eggs (from the phone)")

        engine.downloadAllNotes().getOrThrow()

        assertTrue(transport.deleteAttempts.none { it.noteId == 1L })
        assertEquals("milk, eggs (from the phone)", noteDao.notes[1L]?.content)
        assertFalse(stateStore.isDeleted(1L))
        assertNull(stateStore.deletedDigestById()[1L])
    }

    @Test
    fun `the fingerprint matches the web client's for the same words`() {
        // Vectors produced by web authoredContentDigest (conflictResolution.ts) for the same notes.
        assertEquals(
            "k48zx5vdvr",
            ConflictResolution.authoredContentDigest(
                Note(id = 1L, title = "Groceries", content = "milk\nbread", timestamp = 0L, color = 0),
            ),
        )
        assertEquals(
            "uwzh4mcxr7",
            ConflictResolution.authoredContentDigest(
                Note(
                    id = 2L, title = "Café ✓ 日本", content = "", timestamp = 0L, color = 0,
                    checklist = listOf(
                        ChecklistItem(text = "eggs", isChecked = true, position = 1),
                        ChecklistItem(text = "oat milk", isChecked = false, position = 0),
                    ),
                    labels = listOf(Label(id = 2L, name = "home"), Label(id = 1L, name = "Errands")),
                ),
            ),
        )
    }
}
