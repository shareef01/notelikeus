package com.aus.notelikeus.ui.navigation

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationIntentsTest {

    @Test
    fun `extractEditorNoteId reads noteId extra`() {
        val intent = Intent().putExtra("noteId", 42L)
        assertEquals(42L, extractEditorNoteId(intent))
    }

    @Test
    fun `extractEditorNoteId reads editor deep link uri`() {
        val intent = Intent().apply {
            data = android.net.Uri.parse("notelikeus://editor/99")
        }
        assertEquals(99L, extractEditorNoteId(intent))
    }

    @Test
    fun `extractEditorNoteId returns null when missing`() {
        assertNull(extractEditorNoteId(Intent()))
    }

    @Test
    fun `intentRequestsNewNote detects create flag`() {
        assertTrue(intentRequestsNewNote(Intent().putExtra("createNote", true)))
        assertFalse(intentRequestsNewNote(Intent()))
    }
}
