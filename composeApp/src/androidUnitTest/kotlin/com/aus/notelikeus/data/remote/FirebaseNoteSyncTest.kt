package com.aus.notelikeus.data.remote

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QueryDocumentSnapshot
import com.google.firebase.firestore.WriteBatch
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FirebaseNoteSyncTest {

    private lateinit var noteRepository: NoteRepository
    private lateinit var sessionManager: FirebaseSessionManager
    private lateinit var firestore: FirebaseFirestore
    private lateinit var syncStateStore: NoteSyncStateStore
    private lateinit var sync: FirebaseNoteSync

    @Before
    fun setup() {
        noteRepository = mockk(relaxed = true)
        sessionManager = mockk()
        firestore = mockk(relaxed = true)
        syncStateStore = mockk(relaxed = true)
        every { syncStateStore.isDeleted(any()) } returns false
        every { syncStateStore.deletedAtById() } returns emptyMap()
        every { syncStateStore.restoredIds() } returns emptySet()
        // Steady state: local data has already been confirmed to belong to "uid" (see the
        // lastMergedUserId guard test below for the account-switch-race case).
        every { syncStateStore.lastMergedUserId() } returns "uid"
        sync = FirebaseNoteSync(noteRepository, sessionManager, firestore, syncStateStore)
    }

    private fun stubUserCollections(
        notesCollection: CollectionReference,
        metaCollection: CollectionReference = mockk(relaxed = true),
        tombstonesCollection: CollectionReference = mockk(relaxed = true)
    ) {
        every { firestore.collection("users") } returns mockk(relaxed = true) {
            every { document("uid") } returns mockk(relaxed = true) {
                every { collection("notes") } returns notesCollection
                every { collection("_meta") } returns metaCollection
                every { collection("tombstones") } returns tombstonesCollection
            }
        }
        every { tombstonesCollection.get() } returns Tasks.forResult(mockk(relaxed = true) {
            every { documents } returns emptyList()
        })
        every { tombstonesCollection.document(any()) } returns mockk(relaxed = true) {
            every { get() } returns Tasks.forResult(mockk(relaxed = true) {
                every { exists() } returns false
            })
            every { set(any<Map<String, Any>>(), any()) } returns Tasks.forResult(null)
        }
    }

    @Test
    fun `uploadAllNotes fails when Google sign-in is required`() = runTest {
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.failure(
            IllegalStateException("Google sign-in required")
        )

        val result = sync.uploadAllNotes()

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { noteRepository.getAllNotesForBackup() }
    }

    @Test
    fun `uploadAllNotes uploads every note`() = runTest {
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        val first = Note(id = 1L, title = "Open", content = "", timestamp = 1L, color = 0)
        val second = Note(id = 2L, title = "Also open", content = "", timestamp = 2L, color = 0)
        coEvery { noteRepository.getAllNotesForBackup() } returns listOf(first, second)

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val metaCollection = mockk<CollectionReference>(relaxed = true)
        val batch = mockk<WriteBatch>(relaxed = true)
        stubUserCollections(notesCollection, metaCollection)
        every { notesCollection.document(any()) } returns mockk(relaxed = true) {
            // Post-write readback (see putCloudNote/refreshServerTimestamp) needs a completed
            // Task, or awaiting an entirely unstubbed one on a relaxed mock never resumes.
            every { get() } returns Tasks.forResult(mockk(relaxed = true))
        }
        every { notesCollection.get() } returns Tasks.forResult(mockk(relaxed = true) {
            every { documents } returns emptyList()
        })
        every { metaCollection.document("sync") } returns mockk(relaxed = true) {
            every { set(any<Map<String, Any>>(), any()) } returns Tasks.forResult(null)
        }
        every { firestore.batch() } returns batch
        every { batch.set(any(), any<Map<String, Any?>>(), any()) } returns batch
        every { batch.commit() } returns Tasks.forResult(null)

        val result = sync.uploadAllNotes()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull())
        verify(exactly = 2) { batch.set(any(), any<Map<String, Any?>>(), any()) }
    }

    @Test
    fun `uploadNote refreshes the local serverUpdatedAt cache after a successful upload`() = runTest {
        // Without this readback, Room's cached serverUpdatedAt goes stale the moment this device
        // uploads its own edit, and a later reconcile/download pass could mistake a fresh remote
        // timestamp (from this very upload) for evidence that remote is ahead of local.
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        val note = Note(id = 7L, title = "Edited", content = "", timestamp = 1L, color = 0)
        coEvery { noteRepository.getNoteById(7L) } returns note

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val tombstonesCollection = mockk<CollectionReference>(relaxed = true)
        val noteDoc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection, tombstonesCollection = tombstonesCollection)
        every { notesCollection.document("7") } returns noteDoc
        every { noteDoc.set(any<Map<String, Any?>>(), any()) } returns Tasks.forResult(null)
        // Pre-upload conflict check: no remote (or no newer remote). Post-write readback: 42s.
        every { noteDoc.get() } returnsMany listOf(
            Tasks.forResult(mockk(relaxed = true) {
                every { exists() } returns false
            }),
            Tasks.forResult(mockk(relaxed = true) {
                every { getTimestamp("serverUpdatedAt") } returns Timestamp(42L, 0)
            }),
        )
        every { tombstonesCollection.document("7") } returns mockk(relaxed = true) {
            every { get() } returns Tasks.forResult(mockk(relaxed = true) {
                every { exists() } returns false
            })
        }

        val result = sync.uploadNote(7L)

        assertTrue(result.isSuccess)
        coVerify { noteRepository.updateServerTimestamp(7L, 42_000L) }
    }

    @Test
    fun `uploadNote skips write when remote serverUpdatedAt is newer or equal`() = runTest {
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        val note = Note(
            id = 7L,
            title = "Stale",
            content = "",
            timestamp = 1L,
            color = 0,
            serverUpdatedAt = 10_000L,
        )
        coEvery { noteRepository.getNoteById(7L) } returns note

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val tombstonesCollection = mockk<CollectionReference>(relaxed = true)
        val noteDoc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection, tombstonesCollection = tombstonesCollection)
        every { notesCollection.document("7") } returns noteDoc
        every { noteDoc.get() } returns Tasks.forResult(mockk(relaxed = true) {
            every { exists() } returns true
            every { getTimestamp("serverUpdatedAt") } returns Timestamp(20L, 0)
        })
        every { tombstonesCollection.document("7") } returns mockk(relaxed = true) {
            every { get() } returns Tasks.forResult(mockk(relaxed = true) {
                every { exists() } returns false
            })
        }

        val result = sync.uploadNote(7L)

        assertTrue(result.isSuccess)
        verify(exactly = 0) { noteDoc.set(any<Map<String, Any?>>(), any()) }
        coVerify(exactly = 0) { noteRepository.updateServerTimestamp(any(), any()) }
    }

    @Test
    fun `uploadNote pushes a reordered note when serverUpdatedAt ties but local client timestamp is newer`() = runTest {
        // A drag-reorder bumps the local client timestamp but leaves serverUpdatedAt unchanged
        // (see NoteRepositoryImpl.updateNotePositions), so the cloud copy still ties on the
        // server timestamp. The newer local client timestamp must win the tie-break and the
        // position must be pushed — otherwise reorders never propagate to the cloud.
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        val note = Note(
            id = 7L,
            title = "Moved",
            content = "",
            timestamp = 5_000L,
            color = 0,
            position = 3,
            serverUpdatedAt = 10_000L,
        )
        coEvery { noteRepository.getNoteById(7L) } returns note

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val noteDoc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection)
        every { notesCollection.document("7") } returns noteDoc
        every { noteDoc.set(any<Map<String, Any?>>(), any()) } returns Tasks.forResult(null)
        every { noteDoc.get() } returnsMany listOf(
            // Pre-upload conflict check: remote ties on serverUpdatedAt but carries the stale
            // pre-reorder client timestamp.
            Tasks.forResult(mockk(relaxed = true) {
                every { exists() } returns true
                every { getTimestamp("serverUpdatedAt") } returns Timestamp(10L, 0)
                every { getLong("timestamp") } returns 1_000L
            }),
            // Post-write readback that resolves the server-assigned timestamp.
            Tasks.forResult(mockk(relaxed = true) {
                every { getTimestamp("serverUpdatedAt") } returns Timestamp(42L, 0)
            }),
        )

        val result = sync.uploadNote(7L)

        assertTrue(result.isSuccess)
        verify(exactly = 1) { noteDoc.set(any<Map<String, Any?>>(), any()) }
        coVerify { noteRepository.updateServerTimestamp(7L, 42_000L) }
    }

    @Test
    fun `uploadNote respects cloud tombstone and deletes instead of writing`() = runTest {
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        var locallyDeleted = false
        every { syncStateStore.mergeDeleted(any()) } answers {
            locallyDeleted = true
            Unit
        }
        every { syncStateStore.isDeleted(11L) } answers { locallyDeleted }
        every { syncStateStore.deletedAtById() } returns mapOf(11L to 99L)

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val tombstonesCollection = mockk<CollectionReference>(relaxed = true)
        val noteDoc = mockk<DocumentReference>(relaxed = true)
        val tombstoneDoc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection, tombstonesCollection = tombstonesCollection)
        every { notesCollection.document("11") } returns noteDoc
        every { noteDoc.delete() } returns Tasks.forResult(null)
        every { tombstonesCollection.document("11") } returns tombstoneDoc
        every { tombstoneDoc.get() } returns Tasks.forResult(mockk(relaxed = true) {
            every { exists() } returns true
            every { getLong("deletedAt") } returns 99L
        })
        every { tombstoneDoc.set(any<Map<String, Any>>(), any()) } returns Tasks.forResult(null)

        val result = sync.uploadNote(11L)

        assertTrue(result.isSuccess)
        verify { syncStateStore.mergeDeleted(mapOf(11L to 99L)) }
        verify { noteDoc.delete() }
        verify { syncStateStore.markDeleted(11L, any()) }
        coVerify(exactly = 0) { noteRepository.getNoteById(any()) }
    }

    @Test
    fun `restoreNote clears both tombstones and re-uploads the note`() = runTest {
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        val restored = Note(id = 11L, title = "Back", content = "body", timestamp = 5L, color = 0)
        coEvery { noteRepository.getNoteById(11L) } returns restored

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val tombstonesCollection = mockk<CollectionReference>(relaxed = true)
        val noteDoc = mockk<DocumentReference>(relaxed = true)
        val tombstoneDoc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection, tombstonesCollection = tombstonesCollection)
        every { notesCollection.document("11") } returns noteDoc
        every { noteDoc.set(any<Map<String, Any?>>(), any()) } returns Tasks.forResult(null)
        // Post-write readback (see putCloudNote) needs a completed Task.
        every { noteDoc.get() } returns Tasks.forResult(mockk(relaxed = true))
        every { tombstonesCollection.document("11") } returns tombstoneDoc
        every { tombstoneDoc.delete() } returns Tasks.forResult(null)

        val result = sync.restoreNote(11L)

        assertTrue(result.isSuccess)
        verify { syncStateStore.clearDeleted(listOf(11L)) }
        verify { tombstoneDoc.delete() }
        verify { noteDoc.set(any<Map<String, Any?>>(), any()) }
        verify(exactly = 0) { noteDoc.delete() }
    }

    @Test
    fun `a sync after a failed restore finishes the cleanup instead of re-deleting the note`() = runTest {
        // The restore's own work exhausted its retries, so the cloud tombstone is still there
        // and the marker is still set. This sync must delete that tombstone rather than merge it.
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        every { syncStateStore.restoredIds() } returns setOf(11L)
        coEvery { noteRepository.getAllNotesForBackup() } returns emptyList()

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val metaCollection = mockk<CollectionReference>(relaxed = true)
        val tombstonesCollection = mockk<CollectionReference>(relaxed = true)
        val tombstoneDoc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection, metaCollection, tombstonesCollection)
        every { tombstonesCollection.get() } returns Tasks.forResult(mockk(relaxed = true) {
            every { documents } returns listOf(mockk(relaxed = true) {
                every { id } returns "11"
                every { getLong("deletedAt") } returns 500L
            })
        })
        every { tombstonesCollection.document("11") } returns tombstoneDoc
        every { tombstoneDoc.delete() } returns Tasks.forResult(null)
        every { metaCollection.document("sync") } returns mockk(relaxed = true) {
            every { set(any<Map<String, Any>>(), any()) } returns Tasks.forResult(null)
        }

        val result = sync.uploadAllNotes()

        assertTrue(result.isSuccess)
        verify { tombstoneDoc.delete() }
        verify { syncStateStore.clearRestored(listOf(11L)) }
        // The crucial part: the stale tombstone must not come back into the local store.
        verify(exactly = 0) { syncStateStore.mergeDeleted(match { it.containsKey(11L) }) }
    }

    @Test
    fun `reconcileUploads pushes only notes changed since the last reconcile and does no full read`() = runTest {
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        every { syncStateStore.lastReconciledAt() } returns 100L
        val changed = Note(id = 1L, title = "New", content = "", timestamp = 200L, color = 0)
        val unchanged = Note(id = 2L, title = "Old", content = "", timestamp = 50L, color = 0)
        coEvery { noteRepository.getAllNotesForBackup() } returns listOf(changed, unchanged)
        coEvery { noteRepository.getCloudEligibleNoteCount() } returns 2

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val metaCollection = mockk<CollectionReference>(relaxed = true)
        val changedDoc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection, metaCollection)
        every { notesCollection.document("1") } returns changedDoc
        every { changedDoc.get() } returns Tasks.forResult(mockk(relaxed = true) {
            every { exists() } returns false
        })
        every { changedDoc.set(any<Map<String, Any?>>(), any()) } returns Tasks.forResult(null)
        every { metaCollection.document("sync") } returns mockk(relaxed = true) {
            every { set(any<Map<String, Any>>(), any()) } returns Tasks.forResult(null)
        }

        val result = sync.reconcileUploads()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
        verify { changedDoc.set(any<Map<String, Any?>>(), any()) }
        verify(exactly = 0) { notesCollection.document("2") }
        verify(exactly = 0) { notesCollection.get() }
        verify { syncStateStore.markReconciled(any()) }
    }

    @Test
    fun `reconcileUploads does not clobber a newer cloud copy`() = runTest {
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        every { syncStateStore.lastReconciledAt() } returns 0L
        val local = Note(id = 5L, title = "Local", content = "", timestamp = 100L, color = 0)
        coEvery { noteRepository.getAllNotesForBackup() } returns listOf(local)
        coEvery { noteRepository.getCloudEligibleNoteCount() } returns 1

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val metaCollection = mockk<CollectionReference>(relaxed = true)
        val doc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection, metaCollection)
        every { notesCollection.document("5") } returns doc
        every { doc.get() } returns Tasks.forResult(mockk(relaxed = true) {
            every { exists() } returns true
            every { getLong("timestamp") } returns 999L
        })
        every { metaCollection.document("sync") } returns mockk(relaxed = true) {
            every { set(any<Map<String, Any>>(), any()) } returns Tasks.forResult(null)
        }

        val result = sync.reconcileUploads()

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
        verify(exactly = 0) { doc.set(any<Map<String, Any?>>(), any()) }
    }

    @Test
    fun `reconcileUploads treats equal serverUpdatedAt and equal client timestamps as up to date`() = runTest {
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        every { syncStateStore.lastReconciledAt() } returns 0L
        val local = Note(
            id = 5L,
            title = "Local tie",
            content = "",
            timestamp = 100L,
            color = 0,
            serverUpdatedAt = 50_000L,
        )
        coEvery { noteRepository.getAllNotesForBackup() } returns listOf(local)
        coEvery { noteRepository.getCloudEligibleNoteCount() } returns 1

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val metaCollection = mockk<CollectionReference>(relaxed = true)
        val doc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection, metaCollection)
        every { notesCollection.document("5") } returns doc
        every { doc.get() } returns Tasks.forResult(mockk(relaxed = true) {
            every { exists() } returns true
            every { getTimestamp("serverUpdatedAt") } returns Timestamp(50L, 0)
            every { getLong("timestamp") } returns 100L
        })
        every { metaCollection.document("sync") } returns mockk(relaxed = true) {
            every { set(any<Map<String, Any>>(), any()) } returns Tasks.forResult(null)
        }

        val result = sync.reconcileUploads()

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
        verify(exactly = 0) { doc.set(any<Map<String, Any?>>(), any()) }
    }

    @Test
    fun `reconcileUploads pushes a local edit when serverUpdatedAt ties but local client timestamp is newer`() = runTest {
        // A device that edited locally since its last sync keeps the old serverUpdatedAt, so the
        // cloud copy's serverUpdatedAt matches the local one. The newer local client timestamp
        // must win, otherwise the pending edit never reaches the cloud.
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        every { syncStateStore.lastReconciledAt() } returns 0L
        val local = Note(
            id = 5L,
            title = "Edited locally",
            content = "",
            timestamp = 500L,
            color = 0,
            serverUpdatedAt = 50_000L,
        )
        coEvery { noteRepository.getAllNotesForBackup() } returns listOf(local)
        coEvery { noteRepository.getCloudEligibleNoteCount() } returns 1

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val metaCollection = mockk<CollectionReference>(relaxed = true)
        val doc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection, metaCollection)
        every { notesCollection.document("5") } returns doc
        every { doc.get() } returns Tasks.forResult(mockk(relaxed = true) {
            every { exists() } returns true
            every { getTimestamp("serverUpdatedAt") } returns Timestamp(50L, 0)
            every { getLong("timestamp") } returns 100L
        })
        every { doc.set(any<Map<String, Any?>>(), any()) } returns Tasks.forResult(null)
        every { metaCollection.document("sync") } returns mockk(relaxed = true) {
            every { set(any<Map<String, Any>>(), any()) } returns Tasks.forResult(null)
        }

        val result = sync.reconcileUploads()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
        verify { doc.set(any<Map<String, Any?>>(), any()) }
    }

    @Test
    fun `reconcileUploads skips when local data has not been confirmed for this uid`() = runTest {
        // ReconciliationSyncWorker runs on its own 12h schedule, independent of sign-in. If it
        // fires in the window where MainViewModel.prepareLocalDataForSignedInUser is still
        // wiping a previous account's local notes (before it records lastMergedUserId), it must
        // not upload whatever is currently in Room — that data does not belong to "uid" yet.
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        every { syncStateStore.lastMergedUserId() } returns "some-other-uid"

        val result = sync.reconcileUploads()

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
        coVerify(exactly = 0) { noteRepository.getAllNotesForBackup() }
        verify(exactly = 0) { syncStateStore.markReconciled(any()) }
    }

    @Test
    fun `downloadAllNotes trusts serverUpdatedAt over a skewed client timestamp`() = runTest {
        // Local's own serverUpdatedAt (100) is server-confirmed newer than remote's (50), even
        // though the remote document's client `timestamp` — a different device's clock — reads
        // far higher. Server time must decide the conflict, not either device's clock.
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        // Timestamp's constructor takes seconds, not millis — Timestamp(50, 0) resolves to
        // 50_000ms, so local's serverUpdatedAt has to clear that bar to prove it wins.
        val local = Note(
            id = 5L, title = "Local", content = "", timestamp = 500L, color = 0,
            serverUpdatedAt = 500_000L
        )
        coEvery { noteRepository.getAllNotesForBackup() } returns listOf(local)
        coEvery { noteRepository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { noteRepository.getCloudEligibleNoteCount() } returns 1
        every { syncStateStore.knownCloudIds() } returns emptySet()
        every { syncStateStore.pruneExpired(any()) } returns emptySet()

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val metaCollection = mockk<CollectionReference>(relaxed = true)
        val tombstonesCollection = mockk<CollectionReference>(relaxed = true)
        val remoteDoc = mockk<QueryDocumentSnapshot>(relaxed = true)
        val noteDoc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection, metaCollection, tombstonesCollection)

        every { remoteDoc.id } returns "5"
        every { remoteDoc.data } returns mapOf(
            "title" to "Clock-skewed remote",
            "content" to "",
            "timestamp" to 999_999L,
            "serverUpdatedAt" to Timestamp(50L, 0),
            "color" to 0,
            "isPinned" to false,
            "isArchived" to false,
            "isTrashed" to false,
            "position" to 0,
            "labels" to emptyList<Any>(),
            "checklist" to emptyList<Any>()
        )
        every { notesCollection.get() } returns Tasks.forResult(mockk(relaxed = true) {
            every { documents } returns listOf(remoteDoc)
        })
        every { notesCollection.document("5") } returns noteDoc
        every { noteDoc.set(any<Map<String, Any?>>(), any()) } returns Tasks.forResult(null)
        // Post-write readback (see putCloudNote) needs a completed Task.
        every { noteDoc.get() } returns Tasks.forResult(mockk(relaxed = true))
        every { metaCollection.document("sync") } returns mockk(relaxed = true) {
            every { set(any<Map<String, Any>>(), any()) } returns Tasks.forResult(null)
        }

        val result = sync.downloadAllNotes()

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { noteRepository.updateNote(any()) }
        verify { noteDoc.set(any<Map<String, Any?>>(), any()) }
    }

    @Test
    fun `downloadAllNotes accepts the cloud copy when its serverUpdatedAt is newer`() = runTest {
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        val local = Note(
            id = 5L, title = "Local", content = "", timestamp = 999_999L, color = 0,
            serverUpdatedAt = 10L
        )
        coEvery { noteRepository.getAllNotesForBackup() } returns listOf(local)
        coEvery { noteRepository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { noteRepository.getCloudEligibleNoteCount() } returns 1
        every { syncStateStore.knownCloudIds() } returns emptySet()
        every { syncStateStore.pruneExpired(any()) } returns emptySet()

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val metaCollection = mockk<CollectionReference>(relaxed = true)
        val tombstonesCollection = mockk<CollectionReference>(relaxed = true)
        val remoteDoc = mockk<QueryDocumentSnapshot>(relaxed = true)
        val noteDoc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection, metaCollection, tombstonesCollection)

        every { remoteDoc.id } returns "5"
        every { remoteDoc.data } returns mapOf(
            "title" to "Server-confirmed remote",
            "content" to "",
            "timestamp" to 1L,
            "serverUpdatedAt" to Timestamp(200L, 0),
            "color" to 0,
            "isPinned" to false,
            "isArchived" to false,
            "isTrashed" to false,
            "position" to 0,
            "labels" to emptyList<Any>(),
            "checklist" to emptyList<Any>()
        )
        every { notesCollection.get() } returns Tasks.forResult(mockk(relaxed = true) {
            every { documents } returns listOf(remoteDoc)
        })
        every { notesCollection.document("5") } returns noteDoc
        every { metaCollection.document("sync") } returns mockk(relaxed = true) {
            every { set(any<Map<String, Any>>(), any()) } returns Tasks.forResult(null)
        }

        val result = sync.downloadAllNotes()

        assertTrue(result.isSuccess)
        coVerify { noteRepository.updateNote(match { it.title == "Server-confirmed remote" }) }
        verify(exactly = 0) { noteDoc.set(any<Map<String, Any?>>(), any()) }
    }

    @Test
    fun `downloadAllNotes keeps the local edit when serverUpdatedAt ties but local client timestamp is newer`() = runTest {
        // The data-loss regression: Device B synced at serverUpdatedAt=T, then edited locally
        // while the debounced upload hadn't run yet. The cloud copy still carries serverUpdatedAt=T,
        // so both sides tie on the server clock — the local edit must NOT be overwritten.
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        val local = Note(
            id = 5L, title = "Edited locally", content = "", timestamp = 500L, color = 0,
            serverUpdatedAt = 50_000L
        )
        coEvery { noteRepository.getAllNotesForBackup() } returns listOf(local)
        coEvery { noteRepository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { noteRepository.getCloudEligibleNoteCount() } returns 1
        every { syncStateStore.knownCloudIds() } returns emptySet()
        every { syncStateStore.pruneExpired(any()) } returns emptySet()

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val metaCollection = mockk<CollectionReference>(relaxed = true)
        val tombstonesCollection = mockk<CollectionReference>(relaxed = true)
        val remoteDoc = mockk<QueryDocumentSnapshot>(relaxed = true)
        val noteDoc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection, metaCollection, tombstonesCollection)

        every { remoteDoc.id } returns "5"
        every { remoteDoc.data } returns mapOf(
            "title" to "Stale cloud copy",
            "content" to "",
            "timestamp" to 100L,
            "serverUpdatedAt" to Timestamp(50L, 0),
            "color" to 0,
            "isPinned" to false,
            "isArchived" to false,
            "isTrashed" to false,
            "position" to 0,
            "labels" to emptyList<Any>(),
            "checklist" to emptyList<Any>()
        )
        every { notesCollection.get() } returns Tasks.forResult(mockk(relaxed = true) {
            every { documents } returns listOf(remoteDoc)
        })
        every { notesCollection.document("5") } returns noteDoc
        every { noteDoc.set(any<Map<String, Any?>>(), any()) } returns Tasks.forResult(null)
        every { noteDoc.get() } returns Tasks.forResult(mockk(relaxed = true))
        every { metaCollection.document("sync") } returns mockk(relaxed = true) {
            every { set(any<Map<String, Any>>(), any()) } returns Tasks.forResult(null)
        }

        val result = sync.downloadAllNotes()

        assertTrue(result.isSuccess)
        // The local edit must survive and be pushed to the cloud, not clobbered by the stale copy.
        coVerify(exactly = 0) { noteRepository.updateNote(any()) }
        verify { noteDoc.set(any<Map<String, Any?>>(), any()) }
    }

    @Test
    fun `downloadAllNotes accepts the cloud copy when serverUpdatedAt ties but remote client timestamp is newer`() = runTest {
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        val local = Note(
            id = 5L, title = "Local", content = "", timestamp = 100L, color = 0,
            serverUpdatedAt = 50_000L
        )
        coEvery { noteRepository.getAllNotesForBackup() } returns listOf(local)
        coEvery { noteRepository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { noteRepository.getCloudEligibleNoteCount() } returns 1
        every { syncStateStore.knownCloudIds() } returns emptySet()
        every { syncStateStore.pruneExpired(any()) } returns emptySet()

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val metaCollection = mockk<CollectionReference>(relaxed = true)
        val tombstonesCollection = mockk<CollectionReference>(relaxed = true)
        val remoteDoc = mockk<QueryDocumentSnapshot>(relaxed = true)
        val noteDoc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection, metaCollection, tombstonesCollection)

        every { remoteDoc.id } returns "5"
        every { remoteDoc.data } returns mapOf(
            "title" to "Newer cloud copy",
            "content" to "",
            "timestamp" to 900L,
            "serverUpdatedAt" to Timestamp(50L, 0),
            "color" to 0,
            "isPinned" to false,
            "isArchived" to false,
            "isTrashed" to false,
            "position" to 0,
            "labels" to emptyList<Any>(),
            "checklist" to emptyList<Any>()
        )
        every { notesCollection.get() } returns Tasks.forResult(mockk(relaxed = true) {
            every { documents } returns listOf(remoteDoc)
        })
        every { notesCollection.document("5") } returns noteDoc
        every { metaCollection.document("sync") } returns mockk(relaxed = true) {
            every { set(any<Map<String, Any>>(), any()) } returns Tasks.forResult(null)
        }

        val result = sync.downloadAllNotes()

        assertTrue(result.isSuccess)
        coVerify { noteRepository.updateNote(match { it.title == "Newer cloud copy" }) }
        verify(exactly = 0) { noteDoc.set(any<Map<String, Any?>>(), any()) }
    }

    @Test
    fun `deleteAllCloudData fails when Google sign-in is required`() = runTest {
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.failure(
            IllegalStateException("Google sign-in required")
        )

        val result = sync.deleteAllCloudData()

        assertTrue(result.isFailure)
    }
}
