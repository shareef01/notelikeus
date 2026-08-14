package com.aus.notelikeus.appfunctions

import androidx.appfunctions.AppFunctionContext
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class NoteAppFunctionsTest {

    private lateinit var noteAppFunctions: NoteAppFunctions
    private lateinit var repository: NoteRepository
    private lateinit var reminderManager: ReminderManager
    private lateinit var settingsRepository: SettingsRepository

    /** Flips the app lock for a test; the default in [setup] is unlocked. */
    private fun setAppLocked(locked: Boolean) {
        every { settingsRepository.isAppLockEnabled } returns flowOf(locked)
    }

    /**
     * Every @AppFunction takes the caller's context as its first parameter. Nothing in
     * [NoteAppFunctions] reads it, so a relaxed mock is enough to exercise the bodies.
     */
    private val context: AppFunctionContext = mockk(relaxed = true)

    @Before
    fun setup() {
        repository = mockk(relaxed = true)
        reminderManager = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        setAppLocked(false)

        startKoin {
            modules(
                module {
                    single<NoteRepository> { repository }
                    single<ReminderManager> { reminderManager }
                    single<SettingsRepository> { settingsRepository }
                }
            )
        }
        noteAppFunctions = NoteAppFunctions()
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `createNote inserts note and returns AppFunctionNote`() = runTest {
        coEvery { repository.insertNoteWithResult(any()) } returns 123L

        val result = noteAppFunctions.createNote(context, "New Note", "Content")

        assertEquals(123L, result.id)
        assertEquals("New Note", result.title)
        assertEquals("Content", result.content)
        coVerify {
            repository.insertNoteWithResult(
                match { it.title == "New Note" && it.content == "Content" }
            )
        }
    }

    @Test
    fun `listNotes returns all active notes`() = runTest {
        val notes = listOf(
            Note(id = 1L, title = "A", content = "B", timestamp = 0L, color = 0),
            Note(id = 2L, title = "C", content = "D", timestamp = 0L, color = 0)
        )
        every { repository.getActiveNotes() } returns flowOf(notes)

        val result = noteAppFunctions.listNotes(context)

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

        val result = noteAppFunctions.searchNotes(context, "milk")

        assertEquals(1, result.size)
        assertEquals("Shopping", result.first().title)
    }

    @Test
    fun `addReminder updates note and schedules reminder`() = runTest {
        val note = Note(id = 1L, title = "A", content = "B", timestamp = 0L, color = 0)
        coEvery { repository.getNoteById(1L) } returns note
        val futureTime = System.currentTimeMillis() + 10_000

        val result = noteAppFunctions.addReminder(context, 1L, futureTime)

        assertNotNull(result)
        assertEquals(futureTime, result?.reminderTimestamp)
        coVerify { repository.updateNote(match { it.reminderTimestamp == futureTime }) }
        coVerify { reminderManager.scheduleReminder(1L, futureTime) }
    }

    @Test
    fun `addReminder returns null if note not found`() = runTest {
        coEvery { repository.getNoteById(any()) } returns null

        val result = noteAppFunctions.addReminder(context, 999L, 0L)

        assertNull(result)
    }

    @Test
    fun `archiveNote marks note as archived`() = runTest {
        val note = Note(
            id = 1L,
            title = "A",
            content = "B",
            timestamp = 0L,
            color = 0,
            isArchived = false
        )
        coEvery { repository.getNoteById(1L) } returns note

        val result = noteAppFunctions.archiveNote(context, 1L)

        assertNotNull(result)
        assertEquals(true, result?.isArchived)
        coVerify { repository.updateNote(match { it.isArchived }) }
    }

    @Test
    fun `archiveNote returns null if note not found`() = runTest {
        coEvery { repository.getNoteById(any()) } returns null

        val result = noteAppFunctions.archiveNote(context, 999L)

        assertNull(result)
        coVerify(exactly = 0) { repository.updateNote(any()) }
    }

    // --- App lock ---
    // The UI and the widget both close behind the biometric lock; App Functions is the third
    // surface onto the same notes and must not stay open. Reads would otherwise disclose every
    // note body, and the writes are reachable on a guessed id.

    @Test
    fun `listNotes refuses to read while the app is locked`() = runTest {
        setAppLocked(true)
        every { repository.getActiveNotes() } returns flowOf(
            listOf(Note(id = 1L, title = "Secret", content = "Hidden", timestamp = 0L, color = 0))
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { noteAppFunctions.listNotes(context) }
        }
    }

    @Test
    fun `searchNotes refuses to read while the app is locked`() = runTest {
        setAppLocked(true)
        every { repository.getActiveNotes() } returns flowOf(
            listOf(Note(id = 1L, title = "Secret", content = "Hidden", timestamp = 0L, color = 0))
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { noteAppFunctions.searchNotes(context, "Secret") }
        }
    }

    @Test
    fun `archiveNote refuses to write while the app is locked`() = runTest {
        setAppLocked(true)
        coEvery { repository.getNoteById(1L) } returns
            Note(id = 1L, title = "A", content = "B", timestamp = 0L, color = 0)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { noteAppFunctions.archiveNote(context, 1L) }
        }
        coVerify(exactly = 0) { repository.updateNote(any()) }
    }

    @Test
    fun `createNote refuses to write while the app is locked`() = runTest {
        setAppLocked(true)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { noteAppFunctions.createNote(context, "T", "C") }
        }
        coVerify(exactly = 0) { repository.insertNoteWithResult(any()) }
    }

    @Test
    fun `addReminder refuses to write while the app is locked`() = runTest {
        setAppLocked(true)
        coEvery { repository.getNoteById(1L) } returns
            Note(id = 1L, title = "A", content = "B", timestamp = 0L, color = 0)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { noteAppFunctions.addReminder(context, 1L, 1L) }
        }
        coVerify(exactly = 0) { reminderManager.scheduleReminder(any(), any()) }
    }
}
