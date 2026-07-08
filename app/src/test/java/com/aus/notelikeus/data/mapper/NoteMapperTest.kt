package com.aus.notelikeus.data.mapper

import com.aus.notelikeus.data.local.entity.NoteEntity
import com.aus.notelikeus.domain.model.Note
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteMapperTest {

    @Test
    fun `NoteEntity to Note maps correctly`() {
        val entity = NoteEntity(
            id = 1L,
            title = "Title",
            content = "Content",
            timestamp = 123456L,
            color = 0xFFFFFFFF.toInt(),
            isPinned = true,
            isArchived = false,
            isTrashed = false
        )

        val domain = entity.toNote()

        assertEquals(1L, domain.id)
        assertEquals("Title", domain.title)
        assertEquals("Content", domain.content)
        assertEquals(123456L, domain.timestamp)
        assertEquals(0xFFFFFFFF.toInt(), domain.color)
        assertEquals(true, domain.isPinned)
    }

    @Test
    fun `Note to NoteEntity maps correctly`() {
        val domain = Note(
            id = 1L,
            title = "Title",
            content = "Content",
            timestamp = 123456L,
            color = 0xFFFFFFFF.toInt(),
            isPinned = true
        )

        val entity = domain.toNoteEntity()

        assertEquals(1L, entity.id)
        assertEquals("Title", entity.title)
        assertEquals("Content", entity.content)
        assertEquals(123456L, entity.timestamp)
        assertEquals(0xFFFFFFFF.toInt(), entity.color)
        assertEquals(true, entity.isPinned)
    }
}
