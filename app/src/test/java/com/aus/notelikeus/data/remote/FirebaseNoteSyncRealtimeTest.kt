package com.aus.notelikeus.data.remote

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.google.firebase.firestore.DocumentSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FirebaseNoteSyncRealtimeTest {

    private lateinit var noteRepository: NoteRepository
    private lateinit var sessionManager: FirebaseSessionManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sync: FirebaseNoteSync

    @Before
    fun setup() {
        noteRepository = mockk(relaxed = true)
        sessionManager = mockk()
        settingsRepository = mockk(relaxed = true)
        sync = FirebaseNoteSync(noteRepository, sessionManager, settingsRepository, mockk(relaxed = true))
    }

    @Test
    fun `applyRealtimeSnapshot deletes local note removed from cloud`() = runTest {
        val cloudId = "11111111-1111-4111-8111-111111111111"
        val localNote = Note(
            id = 5L,
            cloudId = cloudId,
            title = "Gone",
            content = "",
            timestamp = 100L,
            color = 0
        )
        coEvery { noteRepository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { noteRepository.getNoteByCloudId(cloudId) } returns localNote
        coEvery { noteRepository.deleteNote(localNote) } returns Unit

        val knownCloudIds = mutableSetOf(cloudId)
        val changes = sync.applyRealtimeSnapshot(emptyList(), knownCloudIds)

        assertEquals(1, changes)
        coVerify { noteRepository.deleteNote(localNote) }
        assertEquals(emptySet<String>(), knownCloudIds)
    }

    @Test
    fun `applyRealtimeSnapshot keeps locked notes when removed from cloud`() = runTest {
        val cloudId = "22222222-2222-4222-8222-222222222222"
        val lockedNote = Note(
            id = 6L,
            cloudId = cloudId,
            title = "Secret",
            content = "",
            timestamp = 100L,
            color = 0,
            isLocked = true
        )
        coEvery { noteRepository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { noteRepository.getNoteByCloudId(cloudId) } returns lockedNote

        val knownCloudIds = mutableSetOf(cloudId)
        val changes = sync.applyRealtimeSnapshot(emptyList(), knownCloudIds)

        assertEquals(0, changes)
        coVerify(exactly = 0) { noteRepository.deleteNote(any()) }
    }

    @Test
    fun `applyRealtimeSnapshot updates newer remote note`() = runTest {
        val cloudId = "33333333-3333-4333-8333-333333333333"
        val localNote = Note(
            id = 7L,
            cloudId = cloudId,
            title = "Old",
            content = "",
            timestamp = 100L,
            color = 0
        )
        val document = mockk<DocumentSnapshot>()
        every { document.id } returns cloudId
        every { document.data } returns mapOf(
            "cloudId" to cloudId,
            "localId" to 7L,
            "title" to "New",
            "content" to "",
            "timestamp" to 200L,
            "color" to 0
        )

        coEvery { noteRepository.getAllLabelsSnapshot() } returns emptyList()
        coEvery { noteRepository.getNoteByCloudId(cloudId) } returns localNote
        coEvery { noteRepository.updateNote(any()) } returns Unit

        val knownCloudIds = mutableSetOf<String>()
        val changes = sync.applyRealtimeSnapshot(listOf(document), knownCloudIds)

        assertEquals(1, changes)
        coVerify { noteRepository.updateNote(match { it.title == "New" }) }
        assertEquals(setOf(cloudId), knownCloudIds)
    }
}
