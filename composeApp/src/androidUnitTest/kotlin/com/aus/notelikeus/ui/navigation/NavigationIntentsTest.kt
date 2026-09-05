package com.aus.notelikeus.ui.navigation

import android.content.Intent
import com.aus.notelikeus.ui.navigation.EXTRA_INTERNAL_NAV
import com.aus.notelikeus.ui.navigation.EXTRA_INTERNAL_NAV_TOKEN
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.ui.navigation.markInternalNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NavigationIntentsTest {

    @Before
    fun setup() {
        InternalNavigationToken.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `extractEditorNoteId reads noteId extra when marked internal`() {
        val intent = Intent().markInternalNavigation().putExtra("noteId", 42L)
        assertEquals(42L, extractEditorNoteId(intent))
    }

    @Test
    fun `extractEditorNoteId ignores unmarked external noteId extra`() {
        val intent = Intent().putExtra("noteId", 42L)
        assertNull(extractEditorNoteId(intent))
    }

    @Test
    fun `extractEditorNoteId ignores forged INTERNAL_NAV boolean without token`() {
        @Suppress("DEPRECATION")
        val intent = Intent()
            .putExtra(EXTRA_INTERNAL_NAV, true)
            .putExtra("noteId", 42L)
        assertNull(extractEditorNoteId(intent))
    }

    @Test
    fun `extractEditorNoteId ignores wrong token`() {
        val intent = Intent()
            .putExtra(EXTRA_INTERNAL_NAV_TOKEN, "forged-token")
            .putExtra("noteId", 42L)
        assertNull(extractEditorNoteId(intent))
    }

    @Test
    fun `extractEditorNoteId reads editor deep link uri when marked internal`() {
        val intent = Intent().markInternalNavigation().apply {
            data = android.net.Uri.parse("notelikeus://editor/99")
        }
        assertEquals(99L, extractEditorNoteId(intent))
    }

    @Test
    fun `extractEditorNoteId returns null when missing`() {
        assertNull(extractEditorNoteId(Intent()))
    }

    @Test
    fun `intentRequestsNewNote detects create flag only when internal`() {
        assertTrue(intentRequestsNewNote(Intent().markInternalNavigation().putExtra("createNote", true)))
        assertFalse(intentRequestsNewNote(Intent().putExtra("createNote", true)))
        assertFalse(intentRequestsNewNote(Intent()))
    }

    /**
     * The sending app chooses these strings. Anything longer than the cloud schema's
     * `notes_title_len` / `notes_content_len` checks saved locally and was then rejected by
     * `apply_note_change` on every later sync, leaving a note that could never upload.
     */
    @Test
    fun `extractSharedText clamps oversized shared text to the cloud limits`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "t".repeat(NoteBackupImporter.MAX_FIELD_CHARS * 2))
            putExtra(Intent.EXTRA_TEXT, "c".repeat(NoteBackupImporter.MAX_CONTENT_CHARS + 5_000))
        }

        val shared = extractSharedText(intent)

        assertNotNull(shared)
        assertEquals(NoteBackupImporter.MAX_FIELD_CHARS, shared!!.first!!.length)
        assertEquals(NoteBackupImporter.MAX_CONTENT_CHARS, shared.second!!.length)
    }

    @Test
    fun `extractSharedText leaves ordinary shared text untouched`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Article title")
            putExtra(Intent.EXTRA_TEXT, "Body worth keeping")
        }

        val shared = extractSharedText(intent)

        assertEquals("Article title", shared?.first)
        assertEquals("Body worth keeping", shared?.second)
    }
}
