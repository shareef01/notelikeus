package com.aus.notelikeus.ui.navigation

import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationIntentsTest {

    @Test
    fun `extractEditorNoteId reads noteId extra`() {
        val intent = mockk<Intent>()
        every { intent.getLongExtra("noteId", -1L) } returns 42L
        every { intent.data } returns null
        assertEquals(42L, extractEditorNoteId(intent))
    }

    @Test
    fun `extractEditorNoteId reads editor deep link uri`() {
        val uri = mockk<Uri>()
        every { uri.scheme } returns "notelikeus"
        every { uri.host } returns "editor"
        every { uri.pathSegments } returns listOf("99")
        every { uri.lastPathSegment } returns "99"
        val intent = mockk<Intent>()
        every { intent.getLongExtra("noteId", -1L) } returns -1L
        every { intent.data } returns uri
        assertEquals(99L, extractEditorNoteId(intent))
    }

    @Test
    fun `extractEditorNoteId returns null when missing`() {
        val intent = mockk<Intent>()
        every { intent.getLongExtra("noteId", -1L) } returns -1L
        every { intent.data } returns null
        assertNull(extractEditorNoteId(intent))
    }

    @Test
    fun `intentRequestsNewNote detects create flag`() {
        val intent = mockk<Intent>()
        every { intent.getBooleanExtra("createNote", false) } returns true
        assertTrue(intentRequestsNewNote(intent))

        val emptyIntent = mockk<Intent>()
        every { emptyIntent.getBooleanExtra("createNote", false) } returns false
        assertFalse(intentRequestsNewNote(emptyIntent))
    }
}
