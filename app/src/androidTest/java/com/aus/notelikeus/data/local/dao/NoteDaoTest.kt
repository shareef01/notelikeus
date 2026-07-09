package com.aus.notelikeus.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteDaoTest {

    private lateinit var database: NotelikeusDatabase
    private lateinit var dao: NoteDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NotelikeusDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.noteDao
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndGetActiveNotes() = runBlocking {
        dao.insertNote(sampleNote(title = "Title"))
        
        val activeNotes = dao.getActiveNotes().first()
        assertEquals(1, activeNotes.size)
        assertEquals("Title", activeNotes[0].note.title)
    }

    @Test
    fun archiveNoteRemovesFromActive() = runBlocking {
        val noteId = dao.insertNote(sampleNote(title = "Title"))
        
        val note = dao.getNoteById(noteId)?.note ?: return@runBlocking
        dao.updateNote(note.copy(isArchived = true))
        
        val activeNotes = dao.getActiveNotes().first()
        val archivedNotes = dao.getArchivedNotes().first()
        
        assertEquals(0, activeNotes.size)
        assertEquals(1, archivedNotes.size)
    }

    @Test
    fun getNextNotePosition_returnsIncrementingValues() = runBlocking {
        dao.insertNote(sampleNote(position = 0))
        dao.insertNote(sampleNote(position = 1))

        assertEquals(2, dao.getNextNotePosition())
    }

    @Test
    fun getAllNotesForBackup_includesArchivedAndTrashed() = runBlocking {
        dao.insertNote(sampleNote(title = "Active"))
        dao.insertNote(sampleNote(title = "Archived", isArchived = true))
        dao.insertNote(sampleNote(title = "Trashed", isTrashed = true))

        val backupNotes = dao.getAllNotesForBackup()

        assertEquals(3, backupNotes.size)
    }

    @Test
    fun getActiveNotes_sortsPinnedFirst() = runBlocking {
        dao.insertNote(sampleNote(title = "Unpinned", isPinned = false, position = 0, timestamp = 100L))
        dao.insertNote(sampleNote(title = "Pinned", isPinned = true, position = 1, timestamp = 50L))

        val activeNotes = dao.getActiveNotes().first()

        assertEquals("Pinned", activeNotes[0].note.title)
        assertEquals("Unpinned", activeNotes[1].note.title)
    }

    @Test
    fun `getWidgetNotes includes checklist items`() = runBlocking {
        val noteId = dao.insertNote(sampleNote(title = "Groceries"))
        dao.insertChecklistItem(
            com.aus.notelikeus.data.local.entity.ChecklistItemEntity(
                noteId = noteId,
                text = "Milk",
                isChecked = false,
                position = 0
            )
        )
        dao.insertChecklistItem(
            com.aus.notelikeus.data.local.entity.ChecklistItemEntity(
                noteId = noteId,
                text = "Eggs",
                isChecked = true,
                position = 1
            )
        )

        val widgetNotes = dao.getWidgetNotes()

        assertEquals(1, widgetNotes.size)
        assertEquals(2, widgetNotes[0].checklist.size)
        assertEquals("Milk", widgetNotes[0].checklist[0].text)
        assertEquals("Eggs", widgetNotes[0].checklist[1].text)
    }

    private fun sampleNote(
        title: String = "Title",
        content: String = "Content",
        isArchived: Boolean = false,
        isTrashed: Boolean = false,
        isPinned: Boolean = false,
        position: Int = 0,
        timestamp: Long = System.currentTimeMillis()
    ) = NoteEntity(
        title = title,
        content = content,
        timestamp = timestamp,
        color = 0,
        isPinned = isPinned,
        isArchived = isArchived,
        isTrashed = isTrashed,
        position = position,
        isLocked = false,
        reminderTimestamp = null
    )
}
