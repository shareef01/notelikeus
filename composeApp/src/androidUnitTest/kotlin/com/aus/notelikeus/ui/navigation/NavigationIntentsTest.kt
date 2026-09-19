package com.aus.notelikeus.ui.navigation

import android.content.Intent
import com.aus.notelikeus.ui.navigation.EXTRA_INTERNAL_NAV
import com.aus.notelikeus.ui.navigation.EXTRA_INTERNAL_NAV_TOKEN
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.ui.navigation.markInternalNavigation
import com.aus.notelikeus.ui.navigation.widgetMainActivityIntent
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
        InternalNavigationToken.forgetInMemoryForTests()
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(InternalNavigationToken.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
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

    @Test
    fun `internal token survives a process death stand-in`() {
        val first = InternalNavigationToken.current()
        val intent = Intent().markInternalNavigation().putExtra("noteId", 7L)

        InternalNavigationToken.forgetInMemoryForTests()
        InternalNavigationToken.init(RuntimeEnvironment.getApplication())

        assertEquals(first, InternalNavigationToken.current())
        assertEquals(7L, extractEditorNoteId(intent))
    }

    @Test
    fun `widgetMainActivityIntent carries NEW_TASK SINGLE_TOP token extra and editor uri`() {
        val intent = widgetMainActivityIntent(RuntimeEnvironment.getApplication(), noteId = 42L)
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            intent.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        assertTrue(InternalNavigationToken.matches(intent))
        assertEquals(42L, intent.getLongExtra("noteId", -1L))
        assertEquals("notelikeus://editor/42", intent.dataString)
        assertEquals(42L, extractEditorNoteId(intent))
    }

    @Test
    fun `extractEditorNoteId still reads the editor uri when the noteId extra is missing`() {
        val intent = widgetMainActivityIntent(RuntimeEnvironment.getApplication(), noteId = 99L)
        intent.removeExtra("noteId")
        assertEquals(99L, extractEditorNoteId(intent))
    }

    @Test
    fun `extractEditorNoteId still reads a legacy reminder note-host uri`() {
        val intent = Intent()
            .markInternalNavigation()
            .setData(android.net.Uri.parse("notelikeus://note/77"))
        assertEquals(77L, extractEditorNoteId(intent))
    }

    @Test
    fun `widgetMainActivityIntent createNote is detected as a new-note request`() {
        val intent = widgetMainActivityIntent(RuntimeEnvironment.getApplication(), createNote = true)
        assertTrue(intentRequestsNewNote(intent))
        assertNull(extractEditorNoteId(intent))
    }

    @Test
    fun `extractExternalShare parses text share correctly`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Headline")
            putExtra(Intent.EXTRA_TEXT, "Story body")
        }
        val share = extractExternalShare(intent)
        assertTrue(share is ExternalShare.Text)
        val textShare = share as ExternalShare.Text
        assertEquals("Headline", textShare.subject)
        assertEquals("Story body", textShare.content)
    }

    @Test
    fun `extractExternalShare clamps oversized subject and content in text share`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "s".repeat(NoteBackupImporter.MAX_FIELD_CHARS + 50))
            putExtra(Intent.EXTRA_TEXT, "b".repeat(NoteBackupImporter.MAX_CONTENT_CHARS + 100))
        }
        val share = extractExternalShare(intent)
        assertTrue(share is ExternalShare.Text)
        val textShare = share as ExternalShare.Text
        assertEquals(NoteBackupImporter.MAX_FIELD_CHARS, textShare.subject?.length)
        assertEquals(NoteBackupImporter.MAX_CONTENT_CHARS, textShare.content?.length)
    }

    @Test
    fun `extractExternalShare rejects empty text share`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "   ")
        }
        assertNull(extractExternalShare(intent))
    }

    @Test
    fun `extractExternalShare parses image share with EXTRA_STREAM`() {
        val uri = android.net.Uri.parse("content://com.android.gallery/photos/101")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Photo Title")
            putExtra(Intent.EXTRA_TEXT, "Photo Caption")
        }
        val share = extractExternalShare(intent)
        assertTrue(share is ExternalShare.Image)
        val imageShare = share as ExternalShare.Image
        assertEquals(uri, imageShare.uri)
        assertEquals("image/png", imageShare.mimeType)
        assertEquals("Photo Title", imageShare.subject)
        assertEquals("Photo Caption", imageShare.content)
    }

    @Test
    fun `extractExternalShare parses image share with ClipData fallback`() {
        val uri = android.net.Uri.parse("content://com.android.providers.media/image/42")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            clipData = android.content.ClipData.newUri(
                RuntimeEnvironment.getApplication().contentResolver,
                "photo",
                uri,
            )
        }
        val share = extractExternalShare(intent)
        assertTrue(share is ExternalShare.Image)
        val imageShare = share as ExternalShare.Image
        assertEquals(uri, imageShare.uri)
        assertEquals("image/jpeg", imageShare.mimeType)
    }

    @Test
    fun `extractExternalShare clamps oversized subject and text in image share`() {
        val uri = android.net.Uri.parse("content://com.example.provider/images/1")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/webp"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "x".repeat(NoteBackupImporter.MAX_FIELD_CHARS * 2))
            putExtra(Intent.EXTRA_TEXT, "y".repeat(NoteBackupImporter.MAX_CONTENT_CHARS * 2))
        }
        val share = extractExternalShare(intent)
        assertTrue(share is ExternalShare.Image)
        val imageShare = share as ExternalShare.Image
        assertEquals(NoteBackupImporter.MAX_FIELD_CHARS, imageShare.subject?.length)
        assertEquals(NoteBackupImporter.MAX_CONTENT_CHARS, imageShare.content?.length)
    }

    @Test
    fun `extractExternalShare rejects image share with missing stream`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
        }
        assertNull(extractExternalShare(intent))
    }

    @Test
    fun `extractExternalShare rejects image share with non-content scheme`() {
        val fileUri = android.net.Uri.parse("file:///sdcard/photo.jpg")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, fileUri)
        }
        assertNull(extractExternalShare(intent))

        val httpUri = android.net.Uri.parse("https://example.com/photo.jpg")
        val httpIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, httpUri)
        }
        assertNull(extractExternalShare(httpIntent))
    }

    @Test
    fun `extractExternalShare rejects non-image non-text MIME`() {
        val uri = android.net.Uri.parse("content://com.example.provider/doc/1")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        assertNull(extractExternalShare(intent))
    }

    @Test
    fun `extractExternalShare rejects unrelated intent action`() {
        val uri = android.net.Uri.parse("content://com.example.provider/image/1")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        assertNull(extractExternalShare(intent))
    }

    @Test
    fun `intentRequestsNewNote returns true for ACTION_SEND with image mime type`() {
        val pngIntent = Intent(Intent.ACTION_SEND).apply { type = "image/png" }
        assertTrue(intentRequestsNewNote(pngIntent))

        val wildcardIntent = Intent(Intent.ACTION_SEND).apply { type = "image/*" }
        assertTrue(intentRequestsNewNote(wildcardIntent))
    }

    @Test
    fun `external image intent containing fake noteId cannot target existing note via extractEditorNoteId`() {
        val uri = android.net.Uri.parse("content://com.example.provider/image/1")
        val maliciousIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra("noteId", 42L)
            putExtra("createNote", false)
        }
        assertNull(extractEditorNoteId(maliciousIntent))
        assertTrue(intentRequestsNewNote(maliciousIntent))
    }
}
