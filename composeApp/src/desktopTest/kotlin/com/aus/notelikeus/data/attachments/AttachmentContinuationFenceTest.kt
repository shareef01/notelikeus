package com.aus.notelikeus.data.attachments

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aus.notelikeus.data.local.NotelikeusDatabase
import com.aus.notelikeus.data.mapper.toNote
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.data.remote.AttachmentBlobTransport
import com.aus.notelikeus.data.remote.AttachmentBlobUploadResult
import com.aus.notelikeus.data.remote.AttachmentRemoteContext
import com.aus.notelikeus.data.repository.NoteRepositoryImpl
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.platform.PlatformWidgetManager
import com.aus.notelikeus.domain.platform.ReminderManager
import com.aus.notelikeus.data.sync.NoopSyncCoordinator
import com.aus.notelikeus.data.sync.RecordingSyncCoordinator
import com.aus.notelikeus.domain.platform.SyncCoordinator
import com.aus.notelikeus.domain.repository.LocalCommitResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Fencing for the **delayed attachment continuation** of a save.
 *
 * The primary note write may already have returned `Applied` in generation N while the attachment
 * work behind it — staged-byte binding, the local rewrite that records uploaded paths, and the
 * removal cleanup — is still running. Those steps mutate account-owned local state, so they must
 * commit under the *originating* token and be refused once isolation has moved the dataset on.
 *
 * Real Room, real [NoteRepositoryImpl], real [AttachmentSyncService] and real file staging; only
 * the platform collaborators and the blob transport are test doubles. No sleeps: ordering is driven
 * by `CompletableDeferred` barriers.
 */
class AttachmentContinuationFenceTest {

    private lateinit var tempDir: File
    private lateinit var database: NotelikeusDatabase
    private lateinit var staging: FileAttachmentStagingStore

    /** Stands in for the signed-in account; the service reads it at execution time. */
    private var owner: String = UID_A

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("notelikeus-attachment-continuation-test").toFile()
        database = Room.databaseBuilder<NotelikeusDatabase>(
            name = File(tempDir, "test.db").absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .build()
        staging = FileAttachmentStagingStore(
            root = File(tempDir, "staging").absolutePath.toPath(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        owner = UID_A
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    // ---- ATT-R1: a valid continuation commits its local state ----

    @Test
    fun `ATT-R1 a valid continuation binds the staged bytes and stores the uploaded paths`() =
        runTest(timeout = TIMEOUT) {
            val transport = RecordingBlobTransport()
            val service = service(transport)
            val repository = repository()

            staging.stage("att-1", UID_A, null, byteArrayOf(1, 2, 3), "image/png")
            database.noteDao.insertNote(noteWith(listOf(pending("att-1"))).toNoteEntity())

            val token = LocalCommitGate.capture(UID_A)

            // 1. Staged binding — local durable state, fenced.
            val bound = service.bindStagedAttachmentsToNote(NOTE_ID, listOf(pending("att-1")), token)
            assertIs<LocalCommitResult.Applied<Unit>>(bound)
            assertEquals(
                NOTE_ID,
                assertNotNull(staging.metadata("att-1", UID_A)).noteId,
                "the staged metadata was not bound to the generated note id",
            )

            // 2. The upload itself is remote work, deliberately outside any gate.
            val synced = service.uploadUnderLiveToken(noteWith(listOf(pending("att-1"))))
            assertEquals(listOf("r2:owners/$UID_A/notes/$NOTE_ID/att-1"), synced.attachments.map { it.storagePath })

            // 3. Recording the uploaded paths is the local write, fenced by the same token.
            val stored = repository.updateNote(synced, token)

            assertIs<LocalCommitResult.Applied<Unit>>(stored)
            val durable = assertNotNull(database.noteDao.getNoteById(NOTE_ID)).note.toNote()
            assertEquals(listOf("att-1"), durable.attachments.map { it.id })
            assertEquals(
                "r2:owners/$UID_A/notes/$NOTE_ID/att-1",
                durable.attachments.single().storagePath,
                "the uploaded path did not reach the note row",
            )
        }

    // ---- ATT-R2: a stale continuation mutates nothing ----

    @Test
    fun `ATT-R2 a stale continuation performs no local durable mutation`() =
        runTest(timeout = TIMEOUT) {
            val transport = RecordingBlobTransport()
            val service = service(transport)
            val repository = repository()

            staging.stage("att-1", UID_A, null, byteArrayOf(1, 2, 3), "image/png")
            database.noteDao.insertNote(noteWith(listOf(pending("att-1"))).toNoteEntity())

            val staleToken = LocalCommitGate.capture(UID_A)
            // The upload has already happened — remote work is not what the fence protects.
            val synced = service.uploadUnderLiveToken(noteWith(listOf(pending("att-1"))))

            LocalCommitGate.isolate { }

            val bound = service.bindStagedAttachmentsToNote(NOTE_ID, listOf(pending("att-1")), staleToken)
            assertIs<LocalCommitResult.StaleGeneration>(bound)
            assertNull(
                assertNotNull(staging.metadata("att-1", UID_A)).noteId,
                "the stale continuation bound the staged metadata anyway",
            )

            val stored = repository.updateNote(synced, staleToken)
            assertIs<LocalCommitResult.StaleGeneration>(stored)
            assertEquals(
                pendingStoragePath("att-1"),
                assertNotNull(database.noteDao.getNoteById(NOTE_ID)).note.toNote()
                    .attachments.single().storagePath,
                "the stale continuation rewrote the note row",
            )

            val removed = service.deleteAttachmentsForNote(NOTE_ID, listOf(pending("att-1")), staleToken)
            assertIs<LocalCommitResult.StaleGeneration>(removed)
            assertNotNull(
                staging.readBytes("att-1", UID_A),
                "the stale continuation released staged bytes it no longer owns",
            )
            assertEquals(
                emptyList(),
                transport.deleted,
                "the stale continuation issued a remote delete under the current identity",
            )
        }

    // ---- ATT-R3: isolation lands while the remote work is parked ----

    @Test
    fun `ATT-R3 isolation during the remote wait rejects the continuation's local commit`() =
        runTest(timeout = TIMEOUT) {
            val transport = RecordingBlobTransport(parkUploads = true)
            val service = service(transport)
            val repository = repository()

            staging.stage("att-1", UID_A, NOTE_ID, byteArrayOf(1, 2, 3), "image/png")
            database.noteDao.insertNote(noteWith(listOf(pending("att-1"))).toNoteEntity())
            val token = LocalCommitGate.capture(UID_A)

            // The primary save, which is what authorizes the continuation in the first place.
            val primary = repository.updateNote(
                noteWith(listOf(pending("att-1"))).copy(title = "saved under A"),
                token,
            )
            assertIs<LocalCommitResult.Applied<Unit>>(primary)

            val upload = async { service.uploadUnderLiveToken(noteWith(listOf(pending("att-1")))) }
            // Parked inside the upload, before any local write of the continuation.
            transport.awaitUploadParked()

            LocalCommitGate.isolate { database.noteDao.deleteAllNotes() }

            transport.releaseUpload()
            val synced = upload.await()

            val stored = repository.updateNote(synced, token)

            assertIs<LocalCommitResult.StaleGeneration>(
                stored,
                "the continuation committed with a token that predates the isolation",
            )
            assertNull(
                database.noteDao.getNoteById(NOTE_ID),
                "the continuation recreated A's note after isolation wiped it",
            )
        }

    // ---- ATT-R4: no fresh-token laundering ----

    @Test
    fun `ATT-R4 the refused continuation carried the original token, not a recaptured one`() =
        runTest(timeout = TIMEOUT) {
            val transport = RecordingBlobTransport(parkUploads = true)
            val service = service(transport)
            val repository = repository()

            staging.stage("att-1", UID_A, NOTE_ID, byteArrayOf(1, 2, 3), "image/png")
            database.noteDao.insertNote(noteWith(listOf(pending("att-1"))).toNoteEntity())
            val token = LocalCommitGate.capture(UID_A)

            val upload = async { service.uploadUnderLiveToken(noteWith(listOf(pending("att-1")))) }
            transport.awaitUploadParked()

            val generationBefore = LocalCommitGate.currentGeneration()
            LocalCommitGate.isolate { database.noteDao.deleteAllNotes() }
            assertTrue(LocalCommitGate.currentGeneration() > generationBefore)

            transport.releaseUpload()
            val synced = upload.await()

            assertIs<LocalCommitResult.StaleGeneration>(repository.updateNote(synced, token))

            // Control: a token captured *now* for the current dataset is accepted, so the refusal
            // above is the token's age and nothing else — had the continuation recaptured, it
            // would have laundered the write into the new dataset.
            val fresh = LocalCommitGate.capture(UID_A)
            assertIs<LocalCommitResult.Applied<Unit>>(repository.updateNote(synced, fresh))
        }

    @Test
    fun `ATT-R6 a stale continuation stops the batch before a later attachment can upload`() =
        runTest(timeout = TIMEOUT) {
            // Two attachments, so the batch has a *later* item whose upload would only be attempted if
            // the stale-stop branch were absent. The first upload is parked, the dataset is replaced
            // while it waits, and the batch is then resumed from the origin token captured before the
            // boundary: the later item's own grant can only be taken under that stale token, which is
            // exactly what must stop the batch rather than drop the attachment from the result.
            val transport = RecordingBlobTransport(parkUploads = true)
            val service = service(transport)

            staging.stage("att-1", UID_A, NOTE_ID, byteArrayOf(1, 2, 3), "image/png")
            staging.stage("att-2", UID_A, NOTE_ID, byteArrayOf(4, 5, 6), "image/png")
            database.noteDao.insertNote(
                noteWith(listOf(pending("att-1"), pending("att-2"))).toNoteEntity(),
            )
            val note = noteWith(listOf(pending("att-1"), pending("att-2")))
            val originToken = LocalCommitGate.capture(UID_A)

            val upload = async { service.syncNoteAttachments(note, originToken) }
            transport.awaitUploadParked()

            val generationBefore = LocalCommitGate.currentGeneration()
            LocalCommitGate.isolate { database.noteDao.deleteAllNotes() }
            assertTrue(LocalCommitGate.currentGeneration() > generationBefore)

            transport.releaseUpload()
            val result = upload.await()

            // The batch is refused, not silently trimmed: the later attachment never reaches the upload
            // path, and the caller is told the dataset it started in is gone instead of receiving a
            // `note` that quietly lost an attachment.
            assertIs<LocalCommitResult.StaleGeneration>(result)
            assertEquals(
                1,
                transport.uploadsStarted,
                "an attachment after the stale one reached the upload path",
            )
        }

    // ---- ATT-R5: stale cleanup cannot delete current-account attachment state ----

    @Test
    fun `ATT-R5 a stale cleanup leaves the current account's attachment state alone`() =
        runTest(timeout = TIMEOUT) {
            // R18: the transport's context follows the live owner, as the real one does — the lane's
            // control below deletes as the *current* account, and a current-generation token paired
            // with another account's context is exactly the incoherence the invariant refuses.
            val transport = RecordingBlobTransport(ownerProvider = { owner })
            val service = service(transport)

            // A's copy of the attachment: same attachment id, same note id.
            staging.stage("att-1", UID_A, NOTE_ID, byteArrayOf(1, 2, 3), "image/png")
            val staleToken = LocalCommitGate.capture(UID_A)

            LocalCommitGate.isolate { }
            owner = UID_B
            // B now owns bytes under the *same* attachment id and note id — the collision that
            // makes an unfenced cleanup dangerous.
            staging.stage("att-1", UID_B, NOTE_ID, byteArrayOf(4, 5, 6), "image/png")

            val staleCleanup = service.deleteAttachmentsForNote(NOTE_ID, listOf(pending("att-1")), staleToken)

            assertIs<LocalCommitResult.StaleGeneration>(staleCleanup)
            assertEquals(
                3,
                assertNotNull(staging.readBytes("att-1", UID_B)).size,
                "the stale A cleanup deleted B's staged attachment",
            )
            assertEquals(
                3,
                assertNotNull(staging.readBytes("att-1", UID_A)).size,
                "the stale A cleanup deleted its own staged bytes",
            )
            assertEquals(emptyList(), transport.deleted, "the stale cleanup issued a remote delete")

            // Control: the same call with a valid token does delete the current owner's copy, so the
            // assertions above are the fence working rather than a no-op.
            val validToken = LocalCommitGate.capture(UID_B)
            assertIs<LocalCommitResult.Applied<Unit>>(
                service.deleteAttachmentsForNote(NOTE_ID, listOf(pending("att-1")), validToken),
            )
            assertNull(staging.readBytes("att-1", UID_B))
        }

    // ---- the security invariant, named ----

    @Test
    fun `attachment continuation from A cannot recreate A note after isolation to B`() =
        runTest(timeout = TIMEOUT) {
            val transport = RecordingBlobTransport(parkUploads = true)
            val service = service(transport)
            val repository = repository()

            // A: note 42 with pending bytes, saved successfully under generation N.
            staging.stage("att-1", UID_A, NOTE_ID, byteArrayOf(1, 2, 3), "image/png")
            database.noteDao.insertNote(noteWith(listOf(pending("att-1"))).toNoteEntity())
            val token = LocalCommitGate.capture(UID_A)
            assertIs<LocalCommitResult.Applied<Unit>>(
                repository.updateNote(noteWith(listOf(pending("att-1"))), token),
            )

            // The upload the continuation is waiting on.
            val upload = async { service.uploadUnderLiveToken(noteWith(listOf(pending("att-1")))) }
            transport.awaitUploadParked()

            // The account switch: A wiped, B established on the same small autoincrement id.
            LocalCommitGate.isolate { database.noteDao.deleteAllNotes() }
            owner = UID_B
            database.noteDao.insertNote(
                Note(
                    id = NOTE_ID,
                    title = "B durable note",
                    content = "B durable content",
                    timestamp = 9_000L,
                    color = 3,
                ).toNoteEntity(),
            )

            // The delayed A continuation resumes and tries to record its uploaded paths.
            transport.releaseUpload()
            val synced = upload.await()
            val stored = repository.updateNote(synced, token)

            assertIs<LocalCommitResult.StaleGeneration>(stored)
            val bNote = assertNotNull(database.noteDao.getNoteById(NOTE_ID)).note.toNote()
            assertEquals("B durable note", bNote.title, "A's continuation overwrote B's note")
            assertEquals("B durable content", bNote.content)
            assertEquals(
                emptyList(),
                bNote.attachments,
                "A's stale continuation attached A's upload to B's note",
            )
        }

    // ---- fixtures ----

    private fun service(transport: AttachmentBlobTransport) = AttachmentSyncService(
        blobTransport = transport,
        metadata = null,
        localStorage = RecordingLocalStorage(),
        noteDao = database.noteDao,
        staging = staging,
        ownerIdProvider = { owner },
        attachmentsEnabled = { true },
    )

    private fun repository() = NoteRepositoryImpl(
        database = database,
        noteDao = database.noteDao,
        labelDao = database.labelDao,
        reminderManager = NoopReminderManager(),
        widgetManager = NoopWidgetManager(),
        syncCoordinator = NoopSyncCoordinator(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun noteWith(attachments: List<Attachment>) = Note(
        id = NOTE_ID,
        title = "A note",
        content = "body",
        timestamp = 1_000L,
        color = 0,
        attachments = attachments,
    )

    private fun pending(id: String) = Attachment(
        id = id,
        noteId = NOTE_ID,
        storagePath = pendingStoragePath(id),
        type = "image",
        mimeType = "image/png",
        sizeBytes = 3,
    )

    private class RecordingBlobTransport(
        private val parkUploads: Boolean = false,
        private val ownerProvider: () -> String = { UID_A },
    ) : AttachmentBlobTransport {
        val deleted = mutableListOf<Pair<String, String>>()

        /** Every upload that reached the transport, counted where the remote call would start (ATT-R6). */
        var uploadsStarted = 0
        private val parked = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        suspend fun awaitUploadParked() = parked.await()

        fun releaseUpload() {
            released.complete(Unit)
        }

        override suspend fun captureContext(): AttachmentRemoteContext =
            AttachmentRemoteContext(ownerId = ownerProvider(), accessToken = "token-${ownerProvider()}")

        override suspend fun upload(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
            bytes: ByteArray,
            mimeType: String,
        ): AttachmentBlobUploadResult {
            uploadsStarted++
            if (parkUploads) {
                parked.complete(Unit)
                released.await()
            }
            return AttachmentBlobUploadResult(
                objectKey = "owners/${context.ownerId}/notes/$noteId/$attachmentId",
                sizeBytes = bytes.size.toLong(),
                mimeType = mimeType,
            )
        }

        override suspend fun download(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
        ): ByteArray = ByteArray(0)

        override suspend fun delete(
            context: AttachmentRemoteContext,
            noteId: String,
            attachmentId: String,
        ) {
            deleted += noteId to attachmentId
        }
    }

    private class RecordingLocalStorage : AttachmentLocalStorage {
        val deleted = mutableListOf<String>()

        override fun persistImageBytes(bytes: ByteArray, extension: String): String? = null

        override fun readBytes(storagePath: String): ByteArray? = null

        override fun deleteIfLocal(storagePath: String) {
            deleted += storagePath
        }
    }

    private class NoopReminderManager : ReminderManager {
        override fun scheduleReminder(noteId: Long, timestamp: Long) {}

        override fun cancelReminder(noteId: Long) {}
    }

    private class NoopWidgetManager : PlatformWidgetManager {
        override suspend fun refreshWidgets() {}
    }

    /**
     * Uploads one note under a **live** generation token that claims no account.
     *
     * These lanes are about the local fence, the immutable remote identity and the staged-bytes
     * bookkeeping rather than about which generation authorizes an upload —
     * `AttachmentUploadRemoteStartFenceTest` covers that, and `syncNoteAttachments` no longer has an
     * un-authorized path to drive: every upload takes a grant from the caller's token. The token here
     * is taken for this call, so its generation is current and the upload proceeds exactly as the lane
     * expects. Its null initiating uid is the guest/staging shape these fixtures model, so the
     * owner-coherence check (covered by AURSF-11/12) deliberately does not apply to them.
     */
    private suspend fun AttachmentSyncService.uploadUnderLiveToken(note: Note): Note =
        when (val uploaded = syncNoteAttachments(note, LocalCommitGate.capture(null))) {
            is LocalCommitResult.Applied -> uploaded.value
            LocalCommitResult.StaleGeneration ->
                error("the token was taken for this call, so its generation cannot already be stale")
        }
    private companion object {
        const val UID_A = "uid-A"
        const val UID_B = "uid-B"
        const val NOTE_ID = 42L
        val TIMEOUT = 60.seconds
    }
}
