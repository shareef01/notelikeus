package com.aus.notelikeus.data.remote

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
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
    fun `uploadAllNotes skips locked notes`() = runTest {
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        val unlocked = Note(id = 1L, title = "Open", content = "", timestamp = 1L, color = 0)
        val locked = Note(id = 2L, title = "Secret", content = "", timestamp = 2L, color = 0, isLocked = true)
        coEvery { noteRepository.getAllNotesForBackup() } returns listOf(unlocked, locked)

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val metaCollection = mockk<CollectionReference>(relaxed = true)
        val batch = mockk<WriteBatch>(relaxed = true)
        stubUserCollections(notesCollection, metaCollection)
        every { notesCollection.document(any()) } returns mockk(relaxed = true)
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
        assertEquals(1, result.getOrNull())
        verify(exactly = 1) { batch.set(any(), any<Map<String, Any?>>(), any()) }
    }

    @Test
    fun `uploadNote on locked note removes it from cloud`() = runTest {
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        val locked = Note(id = 9L, title = "Secret", content = "", timestamp = 1L, color = 0, isLocked = true)
        coEvery { noteRepository.getNoteById(9L) } returns locked

        val document = mockk<DocumentReference>(relaxed = true)
        val notesCollection = mockk<CollectionReference>(relaxed = true)
        stubUserCollections(notesCollection)
        every { notesCollection.document("9") } returns document
        every { document.delete() } returns Tasks.forResult(null)

        val result = sync.uploadNote(9L)

        assertTrue(result.isSuccess)
        verify { document.delete() }
        verify(exactly = 0) { syncStateStore.markDeleted(any(), any()) }
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
    fun `restoreNote skips the upload for a locked note but still clears the tombstone`() = runTest {
        coEvery { sessionManager.ensureGoogleSignedIn() } returns Result.success("uid")
        val locked = Note(id = 12L, title = "Secret", content = "", timestamp = 5L, color = 0, isLocked = true)
        coEvery { noteRepository.getNoteById(12L) } returns locked

        val notesCollection = mockk<CollectionReference>(relaxed = true)
        val tombstonesCollection = mockk<CollectionReference>(relaxed = true)
        val noteDoc = mockk<DocumentReference>(relaxed = true)
        val tombstoneDoc = mockk<DocumentReference>(relaxed = true)
        stubUserCollections(notesCollection, tombstonesCollection = tombstonesCollection)
        every { notesCollection.document("12") } returns noteDoc
        every { tombstonesCollection.document("12") } returns tombstoneDoc
        every { tombstoneDoc.delete() } returns Tasks.forResult(null)

        val result = sync.restoreNote(12L)

        assertTrue(result.isSuccess)
        verify { syncStateStore.clearDeleted(listOf(12L)) }
        verify { tombstoneDoc.delete() }
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
