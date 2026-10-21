package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.attachments.AttachmentLocalStorage
import com.aus.notelikeus.data.attachments.AttachmentSyncService
import com.aus.notelikeus.data.attachments.FileAttachmentStagingStore
import com.aus.notelikeus.data.attachments.NoteAttachmentMetadata
import com.aus.notelikeus.data.attachments.PendingDeletedAttachment
import com.aus.notelikeus.data.attachments.mergeAttachmentsIntoNotes
import com.aus.notelikeus.data.attachments.pendingStoragePath
import com.aus.notelikeus.data.mapper.toNote
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentBlobUploadResult
import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.remote.AttachmentRemoteMetadata
import com.aus.notelikeus.data.remote.OperationRemoteIdentity
import com.aus.notelikeus.data.remote.SupabaseTransportException
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Uploads use the deferred protocol: an attachment row stays provisional, and out of the listing that
 * hydration reads, until a note commit names it. So a note whose commit has not landed must keep its
 * own uploads through hydration, and a conflict on the commit that was to name an upload must not
 * strand it — the conflicted copy takes it.
 */
class DeferredUploadCommitTest {

    private lateinit var tempDir: File
    private val noteDao = FakeNoteDao()
    private val stateStore = FakeNoteSyncStateStore()

    /** Committed rows only, as `list_user_attachments` answers. */
    private val committedRows = mutableListOf<NoteAttachmentMetadata>()
    private val objects = mutableMapOf<Pair<String, String>, ByteArray>()
    private val uploads = mutableListOf<Pair<String, ByteArray>>()

    /** Ids whose next commit the cloud refuses, as it refuses a stale base revision. */
    private val refused = mutableSetOf<Long>()

    /**
     * When set, the fakes behave as the deployed Worker and `apply_note_change` do: an upload is
     * refused (403) until the server holds its note, an accepted upload's row is provisional, and a
     * note commit promotes exactly the provisional rows its `r2:` attachments name.
     */
    private var serverEnforcesCommitment = false

    /** When set, the next push carrying an `r2:` reference — the recommit — loses its connection. */
    private var dropNextRecommit = false
    private val provisionalRows = mutableMapOf<String, NoteAttachmentMetadata>()

    /** A note whose every push fails with an error that is not a conflict, such as a 400. */
    private var alwaysFailingNote: Long? = null

    private val transport = object : FakeCloudNoteTransport() {
        override suspend fun putNotes(uid: String, notes: List<Note>): Map<Long, CloudNoteTransport.PutResult> {
            if (dropNextRecommit && notes.any { note -> note.attachments.any { it.storagePath.startsWith("r2:") } }) {
                dropNextRecommit = false
                throw IOException("connection reset")
            }
            if (alwaysFailingNote != null && notes.any { it.id == alwaysFailingNote }) {
                throw SupabaseTransportException("apply_note_change", 400, "")
            }
            val accepted = notes.filterNot { it.id in refused }
            if (serverEnforcesCommitment) {
                for (note in accepted) {
                    note.attachments.filter { it.storagePath.startsWith("r2:") }
                        .mapNotNull { provisionalRows.remove(it.id) }
                        .forEach { committedRows += it }
                }
            }
            return super.putNotes(uid, accepted)
        }
    }

    private val blobs = object : AttachmentBlobTransport {
        override suspend fun captureContext() = AttachmentRemoteContext(ownerId = UID, accessToken = "token")
        override suspend fun upload(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
            bytes: ByteArray,
            mimeType: String,
        ): AttachmentBlobUploadResult {
            val objectKey = "owners/$UID/notes/$noteId/$attachmentId"
            if (serverEnforcesCommitment) {
                if (noteId.toLong() !in transport.notes) throw SupabaseTransportException("attachments", 403, "")
                provisionalRows[attachmentId] =
                    NoteAttachmentMetadata(attachmentId, noteId, objectKey, mimeType, bytes.size.toLong(), "image")
            }
            uploads += noteId to bytes
            objects[noteId to attachmentId] = bytes
            return AttachmentBlobUploadResult(objectKey, bytes.size.toLong(), mimeType)
        }
        override suspend fun download(context: AttachmentRemoteContext, noteId: String, attachmentId: String) =
            objects.getValue(noteId to attachmentId)
        override suspend fun delete(context: AttachmentRemoteContext, noteId: String, attachmentId: String) = Unit
    }

    private val metadata = object : AttachmentRemoteMetadata {
        override suspend fun register(identity: OperationRemoteIdentity, metadata: NoteAttachmentMetadata) = Unit
        override suspend fun delete(identity: OperationRemoteIdentity, attachmentId: String, noteId: String) = Unit
        override suspend fun listUserAttachments(identity: OperationRemoteIdentity) = committedRows.toList()
        override suspend fun listPendingDeletedAttachments(identity: OperationRemoteIdentity) =
            emptyList<PendingDeletedAttachment>()
        override suspend fun purgeDeleted(identity: OperationRemoteIdentity, attachmentId: String, noteId: String) = Unit
    }

    private val noLocalFiles = object : AttachmentLocalStorage {
        override fun persistImageBytes(bytes: ByteArray, extension: String): String? = null
        override fun readBytes(storagePath: String): ByteArray? = null
        override fun deleteIfLocal(storagePath: String) = Unit
    }

    private lateinit var engine: NoteSyncEngine
    private lateinit var staging: FileAttachmentStagingStore

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("notelikeus-deferred-upload").toFile()
        staging = FileAttachmentStagingStore(
            root = File(tempDir, "staging").absolutePath.toPath(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val attachments = AttachmentSyncService(
            blobTransport = blobs,
            metadata = metadata,
            localStorage = noLocalFiles,
            noteDao = noteDao,
            staging = staging,
            ownerIdProvider = { UID },
            attachmentsEnabled = { true },
        )
        engine = NoteSyncEngine(
            transport = transport,
            remoteIdentityProvider = testRemoteIdentityProvider { UID },
            noteDao = noteDao,
            labelDao = FakeLabelDao(),
            syncStateStore = stateStore,
            uidProvider = { Result.success(UID) },
            attachmentSync = attachments,
            localCommitTokenProvider = { LocalCommitGate.captureCoherent { UID } },
        )
        stateStore.setLastMergedUserId(UID)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun uploaded(id: String, noteId: Long = 1L) =
        Attachment(id = id, noteId = noteId, storagePath = "r2:owners/$UID/notes/$noteId/$id", mimeType = "image/png")

    /** Revision 5 last synced; then edited here, and [image] uploaded, without the commit landing. */
    private suspend fun seedUnconfirmedEditWith(image: Attachment) {
        noteDao.insertNote(
            Note(
                id = 1L, title = "Trip", content = "tickets (typed here)", timestamp = 5_000L, color = 0,
                serverUpdatedAt = 1_000L, attachments = listOf(image),
            ).toNoteEntity(),
        )
        objects["1" to image.id] = "photo".encodeToByteArray()
        stateStore.updateKnownServerRevision(1L, 5L)
        stateStore.updateSyncedTimestamps(mapOf(1L to 900L))
        stateStore.setKnownCloudIds(setOf(1L))
    }

    private fun cloudRecord(content: String, serverUpdatedAt: Long, revision: Long) = CloudNoteRecord(
        noteId = 1L, serverUpdatedAt = serverUpdatedAt, clientTimestamp = serverUpdatedAt - 100,
        title = "Trip", content = content, timestamp = serverUpdatedAt - 100, color = 0,
        isPinned = false, isArchived = false, isTrashed = false, position = 0, reminderTimestamp = null,
        labels = emptyList(), checklistItems = emptyList(), revision = revision,
    )

    @Test
    fun `hydration drops an unlisted upload of a confirmed note but keeps one awaiting its commit`() {
        val confirmed = Note(id = 1L, title = "a", content = "", timestamp = 1L, color = 0, attachments = listOf(uploaded("x")))
        val awaiting = confirmed.copy(id = 2L, attachments = listOf(uploaded("y", 2L)))

        val merged = mergeAttachmentsIntoNotes(listOf(confirmed, awaiting), emptyList()) { it.id == 2L }

        assertEquals(emptyList(), merged[0].attachments)
        assertEquals(listOf("y"), merged[1].attachments.map { it.id })
    }

    @Test
    fun `a note whose commit is refused keeps its upload through the download's hydration`() = runTest {
        seedUnconfirmedEditWith(uploaded("att-up"))
        transport.notes[1L] = cloudRecord("tickets", 1_000L, 5L)
        refused += 1L
        // Another note's committed image, so the listing is not empty and hydration runs.
        committedRows += NoteAttachmentMetadata("att-other", "9", "owners/$UID/notes/9/att-other", "image/png", 5L, "image")

        engine.downloadAllNotes().getOrThrow()

        assertEquals(listOf("att-up"), noteDao.notes.getValue(1L).toNote().attachments.map { it.id })
    }

    @Test
    fun `a conflicted copy takes an upload no commit named, and uploads it under the copy`() = runTest {
        seedUnconfirmedEditWith(uploaded("att-new"))
        // Another device's version is newer: the download keeps both.
        transport.notes[1L] = cloudRecord("tickets, passport (from phone)", 2_000L, 6L)

        engine.downloadAllNotes().getOrThrow()

        val copy = noteDao.notes.values.single { it.id != 1L }.toNote()
        val carried = copy.attachments.single()
        assertNotEquals("att-new", carried.id)
        assertEquals(copy.id, carried.noteId)
        assertTrue(uploads.any { (noteId, bytes) -> noteId == copy.id.toString() && bytes.contentEquals("photo".encodeToByteArray()) })
        assertContentEquals("photo".encodeToByteArray(), objects.getValue(copy.id.toString() to carried.id))
    }

    @Test
    fun `a committed upload stays with the original and is not copied`() = runTest {
        seedUnconfirmedEditWith(uploaded("att-old"))
        committedRows += NoteAttachmentMetadata("att-old", "1", "owners/$UID/notes/1/att-old", "image/png", 5L, "image")
        transport.notes[1L] = cloudRecord("tickets, passport (from phone)", 2_000L, 6L)

        engine.downloadAllNotes().getOrThrow()

        assertTrue(noteDao.notes.values.single { it.id != 1L }.toNote().attachments.isEmpty())
        assertEquals(listOf("att-old"), noteDao.notes.getValue(1L).toNote().attachments.map { it.id })
    }

    /**
     * A note this device made with an image — an imported bundle does exactly this — that the server
     * has never seen: its bytes are staged, so its first push cannot upload them yet.
     */
    private suspend fun seedNewNoteWithStagedImage() {
        serverEnforcesCommitment = true
        val photo = "photo".encodeToByteArray()
        staging.stage(attachmentId = "att-imp", ownerId = UID, noteId = 1L, bytes = photo, mimeType = "image/png")
        noteDao.insertNote(
            Note(
                id = 1L, title = "Imported", content = "", timestamp = 5_000L, color = 0,
                attachments = listOf(
                    Attachment(id = "att-imp", noteId = 1L, storagePath = pendingStoragePath("att-imp"), mimeType = "image/png"),
                ),
            ).toNoteEntity(),
        )
    }

    @Test
    fun `an upload deferred on a note's first push is committed once resumed, and survives the next download`() = runTest {
        seedNewNoteWithStagedImage()

        // Pushes the note with its upload deferred, then resumes the upload once the server has it.
        engine.downloadAllNotes().getOrThrow()
        // The next sync, which used to find no row for the upload and drop the image.
        engine.downloadAllNotes().getOrThrow()

        assertEquals(listOf("att-imp"), committedRows.map { it.attachmentId })
        val attachment = noteDao.notes.getValue(1L).toNote().attachments.single()
        assertEquals("att-imp", attachment.id)
        assertTrue(attachment.storagePath.startsWith("r2:"))
    }

    @Test
    fun `a resumed upload's staged copy is released as soon as its recommit is accepted`() = runTest {
        seedNewNoteWithStagedImage()

        // One run: the push defers the upload, reconciliation resumes it, and the recommit lands.
        engine.downloadAllNotes().getOrThrow()

        assertEquals(listOf("att-imp"), committedRows.map { it.attachmentId })
        assertTrue(stateStore.recommitNeededIds().isEmpty())
        // The committed object is the copy now. Left to the unreferenced sweep, these bytes would
        // stay staged for its hour's grace; seen on a Pixel 7 as a picture's size of storage held
        // after its upload.
        assertNull(staging.readBytes("att-imp", UID))
    }

    @Test
    fun `a recommit cut off by a dropped connection is made by the next sync, and the image is kept`() = runTest {
        seedNewNoteWithStagedImage()
        dropNextRecommit = true

        // Pushes the note, resumes the upload, and loses the connection on the push that would commit it.
        assertTrue(engine.downloadAllNotes().isFailure)
        // Back online: this run used to find no row for the upload and drop the image.
        engine.downloadAllNotes().getOrThrow()
        engine.downloadAllNotes().getOrThrow()

        assertEquals(listOf("att-imp"), committedRows.map { it.attachmentId })
        val attachment = noteDao.notes.getValue(1L).toNote().attachments.single()
        assertEquals("att-imp", attachment.id)
        assertTrue(attachment.storagePath.startsWith("r2:"))
        assertTrue(stateStore.recommitNeededIds().isEmpty())
    }

    @Test
    fun `a recommit the server keeps refusing still keeps the image, and stays marked`() = runTest {
        seedNewNoteWithStagedImage()
        dropNextRecommit = true
        assertTrue(engine.downloadAllNotes().isFailure)
        // From here on the cloud refuses every commit of this note.
        refused += 1L

        engine.downloadAllNotes().getOrThrow()

        assertEquals(listOf("att-imp"), noteDao.notes.getValue(1L).toNote().attachments.map { it.id })
        assertEquals(setOf(1L), stateStore.recommitNeededIds())
        // Uncommitted, the upload's server row is still provisional: the staged bytes stay.
        assertContentEquals("photo".encodeToByteArray(), staging.readBytes("att-imp", UID))
    }

    @Test
    fun `a recommit that keeps failing does not stop other devices' notes from arriving`() = runTest {
        seedNewNoteWithStagedImage()
        dropNextRecommit = true
        assertTrue(engine.downloadAllNotes().isFailure)
        // From here on every push of this note fails outright, and another device adds a note.
        alwaysFailingNote = 1L
        transport.notes[2L] = cloudRecord("from the phone", serverUpdatedAt = 9_000L, revision = 9L)
            .copy(noteId = 2L, title = "Groceries")

        // The run still reports the stuck note, but only after the download has run.
        engine.downloadAllNotes()

        assertEquals("from the phone", noteDao.notes.getValue(2L).toNote().content)
        assertEquals(listOf("att-imp"), noteDao.notes.getValue(1L).toNote().attachments.map { it.id })
        assertEquals(setOf(1L), stateStore.recommitNeededIds())
    }

    private companion object {
        const val UID = "uid"
    }
}
