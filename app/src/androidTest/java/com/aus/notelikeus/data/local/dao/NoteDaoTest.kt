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
        val note = NoteEntity(
            title = "Title",
            content = "Content",
            timestamp = System.currentTimeMillis(),
            color = 0,
            isPinned = false,
            isArchived = false,
            isTrashed = false
        )
        dao.insertNote(note)
        
        val activeNotes = dao.getActiveNotes().first()
        assertEquals(1, activeNotes.size)
        assertEquals("Title", activeNotes[0].note.title)
    }

    @Test
    fun archiveNoteRemovesFromActive() = runBlocking {
        val noteId = dao.insertNote(
            NoteEntity(
                title = "Title",
                content = "Content",
                timestamp = System.currentTimeMillis(),
                color = 0,
                isPinned = false,
                isArchived = false,
                isTrashed = false
            )
        )
        
        val note = dao.getNoteById(noteId)?.note ?: return@runBlocking
        dao.updateNote(note.copy(isArchived = true))
        
        val activeNotes = dao.getActiveNotes().first()
        val archivedNotes = dao.getArchivedNotes().first()
        
        assertEquals(0, activeNotes.size)
        assertEquals(1, archivedNotes.size)
    }
}
