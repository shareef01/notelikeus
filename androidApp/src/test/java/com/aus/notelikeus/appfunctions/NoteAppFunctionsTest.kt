package com.aus.notelikeus.appfunctions

import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.platform.ReminderManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class NoteAppFunctionsTest {

    private lateinit var noteAppFunctions: NoteAppFunctions
    private lateinit var repository: NoteRepository
    private lateinit var reminderManager: ReminderManager

    private class TestNoteAppFunctions : NoteAppFunctions() {
        override suspend fun executeFunction(
            request: androidx.appfunctions.ExecuteAppFunctionRequest
        ): androidx.appfunctions.ExecuteAppFunctionResponse {
            throw NotImplementedError()
        }
    }

    @Before
    fun setup() {
        repository = mockk(relaxed = true)
        reminderManager = mockk(relaxed = true)
        
        startKoin {
            modules(module {
                single<NoteRepository> { repository }
                single<ReminderManager> { reminderManager }
            })
        }
        noteAppFunctions = TestNoteAppFunctions()
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `createNote inserts note and returns AppFunctionNote`() = runTest {
        coEvery { repository.insertNoteWithResult(any()) } returns 123L

        val result = noteAppFunctions.createNote("New Note", "Content")

        assertEquals(123L, result.id)
        assertEquals("New Note", result.title)
        assertEquals("Content", result.content)
        coVerify { repository.insertNoteWithResult(match { it.title == "New Note" && it.content == "Content" }) }
    }

    @Test
    fun `listNotes returns all active notes`() = runTest {
        val notes = listOf(
            Note(id = 1L, title = "A", content = "B", timestamp = 0L, color = 0),
            Note(id = 2L, title = "C", content = "D", timestamp = 0L, color = 0)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)

        val result = noteAppFunctions.listNotes()

        assertEquals(2, result.size)
        assertEquals("A", result[0].title)
        assertEquals("C", result[1].title)
    }

    @Test
    fun `searchNotes returns matching notes`() = runTest {
        val notes = listOf(
            Note(id = 1L, title = "Shopping", content = "Buy milk", timestamp = 0L, color = 0),
            Note(id = 2L, title = "Work", content = "Finish audit", timestamp = 0L, color = 0)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)

        val result = noteAppFunctions.searchNotes("milk")

        assertEquals(1, result.size)
        assertEquals("Shopping", result.first().title)
    }

    @Test
    fun `addReminder updates note and schedules reminder`() = runTest {
        val note = Note(id = 1L, title = "A", content = "B", timestamp = 0L, color = 0)
        coEvery { repository.getNoteById(1L) } returns note
        val futureTime = System.currentTimeMillis() + 10000

        val result = noteAppFunctions.addReminder(1L, futureTime)

        assertNotNull(result)
        assertEquals(futureTime, result?.reminderTimestamp)
        coVerify { repository.updateNote(match { it.reminderTimestamp == futureTime }) }
        coVerify { reminderManager.scheduleReminder(1L, futureTime) }
    }

    @Test
    fun `addReminder returns null if note not found`() = runTest {
        coEvery { repository.getNoteById(any()) } returns null

        val result = noteAppFunctions.addReminder(999L, 0L)

        assertNull(result)
    }

    @Test
    fun `archiveNote marks note as archived`() = runTest {
        val note = Note(id = 1L, title = "A", content = "B", timestamp = 0L, color = 0, isArchived = false)
        coEvery { repository.getNoteById(1L) } returns note

        val result = noteAppFunctions.archiveNote(1L)

        assertNotNull(result)
        assertEquals(true, result?.isArchived)
        coVerify { repository.updateNote(match { it.isArchived }) }
    }
}
