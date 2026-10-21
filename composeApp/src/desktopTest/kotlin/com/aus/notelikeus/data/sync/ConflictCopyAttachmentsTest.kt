package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.attachments.AttachmentLocalStorage
import com.aus.notelikeus.data.attachments.AttachmentSyncService
import com.aus.notelikeus.data.attachments.FileAttachmentStagingStore
import com.aus.notelikeus.data.attachments.fileStoragePath
import com.aus.notelikeus.data.attachments.isPendingAttachment
import com.aus.notelikeus.data.attachments.pendingStoragePath
import com.aus.notelikeus.data.mapper.toNote
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.remote.NoopAttachmentBlobTransport
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AU-13: an image that has not reached the cloud exists only on this device. When a conflict turns
 * the local version of a note into a conflicted copy, the copy must take that image with it — the
 * original row is overwritten with the cloud version or deleted, and staged bytes no note references
 * are released at the end of the same download.
 */
class ConflictCopyAttachmentsTest {

    private lateinit var tempDir: File
    private lateinit var staging: FileAttachmentStagingStore
    private val transport = FakeCloudNoteTransport()
    private val stateStore = FakeNoteSyncStateStore()
    private val noteDao = FakeNoteDao()
    private val files = mutableMapOf<String, ByteArray>()

    private val localStorage = object : AttachmentLocalStorage {
        override fun persistImageBytes(bytes: ByteArray, extension: String): String? = null
        override fun readBytes(storagePath: String): ByteArray? = files[storagePath]
        override fun exists(storagePath: String): Boolean = storagePath in files
        override fun deleteIfLocal(storagePath: String) {
            files.remove(storagePath)
        }
    }

    private lateinit var attachments: AttachmentSyncService
    private lateinit var engine: NoteSyncEngine

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("notelikeus-copy-attachments").toFile()
        staging = FileAttachmentStagingStore(
            root = File(tempDir, "staging").absolutePath.toPath(),
            ioDispatcher = Dispatchers.Unconfined,
            // Staged two hours ago: the offline edit these tests seed is long past, and
            // reconciliation only releases unclaimed bytes once they are older than its grace period.
            now = { System.currentTimeMillis() - 2 * 60 * 60 * 1000L },
        )
        attachments = AttachmentSyncService(
            blobTransport = NoopAttachmentBlobTransport(),
            metadata = null,
            localStorage = localStorage,
            noteDao = noteDao,
            staging = staging,
            ownerIdProvider = { UID },
            attachmentsEnabled = { false },
        )
        engine = NoteSyncEngine(
            transport = transport,
            remoteIdentityProvider = testRemoteIdentityProvider { UID },
            noteDao = noteDao,
            labelDao = FakeLabelDao(),
            syncStateStore = stateStore,
            uidProvider = { Result.success(UID) },
            attachmentSync = attachments,
        )
        stateStore.setLastMergedUserId(UID)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** This device last synced revision 5 and then, offline, edited the note and added [image]. */
    private suspend fun seedOfflineEditWith(image: Attachment) {
        noteDao.insertNote(
            Note(
                id = 1L, title = "Trip", content = "tickets (typed offline)",
                timestamp = 5_000L, color = 0, serverUpdatedAt = 1_000L, attachments = listOf(image),
            ).toNoteEntity(),
        )
        stateStore.updateKnownServerRevision(1L, 5L)
        stateStore.updateSyncedTimestamps(mapOf(1L to 900L))
        stateStore.setKnownCloudIds(setOf(1L))
    }

    private fun cloudEdit() = CloudNoteRecord(
        noteId = 1L, serverUpdatedAt = 2_000L, clientTimestamp = 1_900L,
        title = "Trip", content = "tickets, passport (from phone)", timestamp = 1_900L, color = 0,
        isPinned = false, isArchived = false, isTrashed = false,
        position = 0, reminderTimestamp = null,
        labels = emptyList(), checklistItems = emptyList(), revision = 6L,
    )

    private fun copyRow(): Note = noteDao.notes.values.single { it.id != 1L }.toNote()

    @Test
    fun `a forked edit takes its unuploaded image into the copy`() = runTest {
        val photo = "photo bytes".encodeToByteArray()
        assertTrue(attachments.stageAttachment("att-photo", 1L, photo, "image/png"))
        seedOfflineEditWith(
            Attachment(id = "att-photo", noteId = 1L, storagePath = pendingStoragePath("att-photo"), mimeType = "image/png"),
        )
        transport.notes[1L] = cloudEdit()

        engine.downloadAllNotes().getOrThrow()

        val copy = copyRow()
        assertEquals("Trip (conflicted copy)", copy.title)
        val carried = copy.attachments.single()
        assertNotEquals("att-photo", carried.id)
        assertEquals(pendingStoragePath(carried.id), carried.storagePath)
        assertEquals(copy.id, carried.noteId)
        assertContentEquals(photo, staging.readBytes(carried.id, UID))
        // The cloud version kept id 1 and never had the image: it does not keep a second reference,
        // and the bytes staged for it are released by the same download's reconciliation.
        assertTrue(noteDao.notes.getValue(1L).toNote().attachments.isEmpty())
        assertNull(staging.readBytes("att-photo", UID))
    }

    @Test
    fun `a note deleted elsewhere keeps its local image file in the copy`() = runTest {
        val path = fileStoragePath("/pictures/receipt.jpg")
        files[path] = "receipt bytes".encodeToByteArray()
        seedOfflineEditWith(Attachment(id = "att-file", noteId = 1L, storagePath = path, mimeType = "image/jpeg"))
        transport.tombstones[1L] = 2_000L

        engine.downloadAllNotes().getOrThrow()

        assertFalse(1L in noteDao.notes)
        val carried = copyRow().attachments.single()
        assertTrue(isPendingAttachment(carried.storagePath))
        assertContentEquals("receipt bytes".encodeToByteArray(), staging.readBytes(carried.id, UID))
    }

    @Test
    fun `an image whose bytes cannot be read stays on the original rather than vanish`() = runTest {
        // Referenced but never staged: there is nothing to carry, so nothing moves.
        seedOfflineEditWith(
            Attachment(id = "att-missing", noteId = 1L, storagePath = pendingStoragePath("att-missing")),
        )
        transport.notes[1L] = cloudEdit()

        engine.downloadAllNotes().getOrThrow()

        assertTrue(copyRow().attachments.isEmpty())
        assertEquals(listOf("att-missing"), noteDao.notes.getValue(1L).toNote().attachments.map { it.id })
    }

    private companion object {
        const val UID = "uid"
    }
}
