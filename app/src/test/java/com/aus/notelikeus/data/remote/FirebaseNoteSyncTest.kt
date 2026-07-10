package com.aus.notelikeus.data.remote

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
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
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var firestore: FirebaseFirestore
    private lateinit var sync: FirebaseNoteSync

    @Before
    fun setup() {
        noteRepository = mockk(relaxed = true)
        sessionManager = mockk()
        settingsRepository = mockk(relaxed = true)
        firestore = mockk(relaxed = true)
        sync = FirebaseNoteSync(noteRepository, sessionManager, settingsRepository, firestore)
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
        every { firestore.collection("users") } returns mockk(relaxed = true) {
            every { document("uid") } returns mockk(relaxed = true) {
                every { collection("notes") } returns notesCollection
                every { collection("_meta") } returns metaCollection
            }
        }
        every { notesCollection.document(any()) } returns mockk(relaxed = true)
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
        val cloudId = "11111111-1111-4111-8111-111111111111"
        val locked = Note(
            id = 9L,
            cloudId = cloudId,
            title = "Secret",
            content = "",
            timestamp = 1L,
            color = 0,
            isLocked = true
        )
        coEvery { noteRepository.getNoteById(9L) } returns locked

        val document = mockk<DocumentReference>(relaxed = true)
        every { firestore.collection("users") } returns mockk(relaxed = true) {
            every { document("uid") } returns mockk(relaxed = true) {
                every { collection("notes") } returns mockk(relaxed = true) {
                    every { document(cloudId) } returns document
                }
            }
        }
        every { document.delete() } returns Tasks.forResult(null)

        val result = sync.uploadNote(9L)

        assertTrue(result.isSuccess)
        verify { document.delete() }
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
