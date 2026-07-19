package com.aus.notelikeus.ui.navigation

import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        InternalNavigationToken.resetForTests()
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
        val intent = mockk<Intent>()
        every { intent.getLongExtra("noteId", -1L) } returns -1L
        every { intent.data } returns null
        assertNull(extractEditorNoteId(intent))
    }

    @Test
    fun `intentRequestsNewNote detects create flag only when internal`() {
        assertTrue(intentRequestsNewNote(Intent().markInternalNavigation().putExtra("createNote", true)))
        assertFalse(intentRequestsNewNote(Intent().putExtra("createNote", true)))
        assertFalse(intentRequestsNewNote(Intent()))
    }
}
