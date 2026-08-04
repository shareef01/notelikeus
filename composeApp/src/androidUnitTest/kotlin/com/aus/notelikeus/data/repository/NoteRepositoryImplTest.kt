package com.aus.notelikeus.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.dao.LabelDao
import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.remote.ReminderScheduler
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.widget.WidgetUpdater
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class NoteRepositoryImplTest {

    private lateinit var repository: NoteRepositoryImpl
    private lateinit var database: NotelikeusDatabase
    private lateinit var noteDao: NoteDao
    private lateinit var labelDao: LabelDao
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var context: Context

    @Before
    fun setup() {
        database = mockk()
        noteDao = mockk(relaxed = true)
        labelDao = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        context = mockk(relaxed = true)

        // Mock withTransaction to just execute the block
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction<Any>(any()) } coAnswers {
            val block = secondArg<suspend () -> Any>()
            block()
        }

        // Widget refresh touches Glance/AppWidgetManager, which is not available in unit tests.
        mockkObject(WidgetUpdater)
        coEvery { WidgetUpdater.refresh(any()) } returns Unit

        repository = NoteRepositoryImpl(database, noteDao, labelDao, reminderScheduler, context)
    }

    @Test
    fun `insertNoteWithResult inserts note and schedules reminder`() = runTest {
        val note = Note(title = "Test", content = "Content", timestamp = 0L, color = 0)
        coEvery { noteDao.insertNote(any()) } returns 1L

        val result = repository.insertNoteWithResult(note)

        assertEquals(1L, result)
        coVerify { noteDao.insertNote(match { it.title == "Test" }) }
        coVerify { reminderScheduler.cancelReminder(1L) } // No reminder timestamp, so should cancel
    }

    @Test
    fun `updateNote updates note and schedules reminder`() = runTest {
        val reminderTime = System.currentTimeMillis() + 1000
        val note = Note(id = 1L, title = "Test", content = "Content", timestamp = 0L, color = 0, reminderTimestamp = reminderTime)

        repository.updateNote(note)

        coVerify { noteDao.updateNote(match { it.id == 1L }) }
        coVerify { reminderScheduler.scheduleReminder(1L, reminderTime) }
    }

    @Test
    fun `deleteNote cancels reminder and deletes from DAO`() = runTest {
        val note = Note(id = 1L, title = "Test", content = "Content", timestamp = 0L, color = 0)

        repository.deleteNote(note)

        coVerify { reminderScheduler.cancelReminder(1L) }
        coVerify { noteDao.deleteNote(any()) }
    }
}
