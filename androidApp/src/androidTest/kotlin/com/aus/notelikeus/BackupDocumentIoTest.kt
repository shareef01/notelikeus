package com.aus.notelikeus

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The byte handling behind Android's backup buttons, against a real `ContentResolver`.
 *
 * Driving the Storage Access Framework picker itself needs a person, so this covers everything
 * after it hands back a URI: that a backup written to a document reads back byte-for-byte, that
 * an oversized document is refused before it is held in memory, and that an unreadable URI fails
 * rather than throwing into the caller.
 */
@RunWith(AndroidJUnit4::class)
class BackupDocumentIoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver

    private fun tempUri(name: String): Uri =
        Uri.fromFile(File(context.cacheDir, name).also { it.delete() })

    @Test
    fun backupSurvivesAWriteAndReadRoundTrip() {
        val uri = tempUri("round-trip.json")
        val json = """{"version":3,"notes":[{"title":"Kept","content":"body"}]}"""

        assertTrue(BackupDocumentIo.write(resolver, uri, json))

        assertEquals(json, BackupDocumentIo.read(resolver, uri))
    }

    @Test
    fun nonAsciiContentIsNotCorrupted() {
        val uri = tempUri("unicode.json")
        // A note is as likely to hold these as ASCII, and a wrong charset would silently mangle
        // the backup rather than fail it.
        val json = """{"version":3,"notes":[{"title":"Café — 日本語 🗒","content":"naïve"}]}"""

        assertTrue(BackupDocumentIo.write(resolver, uri, json))

        assertEquals(json, BackupDocumentIo.read(resolver, uri))
    }

    @Test
    fun aDocumentLargerThanTheImportLimitIsRefused() {
        val uri = tempUri("too-big.json")
        val oversized = "x".repeat(BackupDocumentIo.MAX_BACKUP_DOCUMENT_CHARS + 1)
        assertTrue(BackupDocumentIo.write(resolver, uri, oversized))

        // Refused while reading rather than after: the importer's own check only runs once the
        // whole document is already a String in memory.
        assertNull(BackupDocumentIo.read(resolver, uri))
    }

    @Test
    fun aDocumentExactlyAtTheLimitIsStillAccepted() {
        val uri = tempUri("at-limit.json")
        val atLimit = "x".repeat(BackupDocumentIo.MAX_BACKUP_DOCUMENT_CHARS)
        assertTrue(BackupDocumentIo.write(resolver, uri, atLimit))

        assertEquals(atLimit.length, BackupDocumentIo.read(resolver, uri)?.length)
    }

    @Test
    fun anUnreadableDocumentFailsInsteadOfThrowing() {
        val missing = Uri.fromFile(File(context.cacheDir, "never-created.json"))

        assertNull(BackupDocumentIo.read(resolver, missing))
    }

    @Test
    fun anUnwritableDocumentReportsFailureInsteadOfThrowing() {
        // A directory is not a document: writing has to report that rather than crash the export.
        val directory = Uri.fromFile(context.cacheDir)

        assertEquals(false, BackupDocumentIo.write(resolver, directory, "{}"))
    }
}
