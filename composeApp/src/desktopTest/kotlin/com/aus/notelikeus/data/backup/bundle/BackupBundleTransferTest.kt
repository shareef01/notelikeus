package com.aus.notelikeus.data.backup.bundle

import com.aus.notelikeus.data.attachments.AttachmentLocalStorage
import com.aus.notelikeus.data.attachments.AttachmentStagingStore
import com.aus.notelikeus.data.attachments.GUEST_STAGING_OWNER
import com.aus.notelikeus.data.attachments.StagedAttachment
import com.aus.notelikeus.data.attachments.fileStoragePath
import com.aus.notelikeus.data.attachments.pendingStoragePath
import com.aus.notelikeus.data.backup.BackupImportResult
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.data.sync.FakeNoteRepository
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BackupBundleTransferTest {

    private class MemoryStaging : AttachmentStagingStore {
        val blobs = mutableMapOf<Pair<String, String>, ByteArray>()

        override suspend fun stage(
            attachmentId: String,
            ownerId: String,
            noteId: Long?,
            bytes: ByteArray,
            mimeType: String,
        ): StagedAttachment {
            blobs[ownerId to attachmentId] = bytes.copyOf()
            return StagedAttachment(
                attachmentId = attachmentId,
                ownerId = ownerId,
                noteId = noteId,
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong(),
                createdAt = 1L,
            )
        }

        override suspend fun readBytes(attachmentId: String, ownerId: String): ByteArray? =
            blobs[ownerId to attachmentId]?.copyOf()

        override suspend fun metadata(attachmentId: String, ownerId: String): StagedAttachment? = null
        override suspend fun bindNote(attachmentId: String, ownerId: String, noteId: Long) = Unit
        override suspend fun release(attachmentId: String, ownerId: String) {
            blobs.remove(ownerId to attachmentId)
        }
        override suspend fun list(ownerId: String): List<StagedAttachment> = emptyList()
        override suspend fun isStaged(attachmentId: String, ownerId: String): Boolean =
            blobs.containsKey(ownerId to attachmentId)
    }

    private class MemoryLocalStorage : AttachmentLocalStorage {
        val files = mutableMapOf<String, ByteArray>()

        override fun persistImageBytes(bytes: ByteArray, extension: String): String? = null
        override fun readBytes(storagePath: String): ByteArray? = files[storagePath]?.copyOf()
        override fun exists(storagePath: String): Boolean = files.containsKey(storagePath)
        override fun deleteIfLocal(storagePath: String) {
            files.remove(storagePath)
        }
    }

    private fun transfer(
        repository: FakeNoteRepository,
        staging: MemoryStaging,
        local: MemoryLocalStorage,
    ) = BackupBundleTransfer(
        repository = repository,
        exporter = NoteBackupExporter(repository, "Notelikeus", "test"),
        importer = NoteBackupImporter(repository),
        staging = staging,
        localStorage = local,
        ownerIdProvider = { null },
        appVersion = "test",
    )

    @Test
    fun exportIncludesLocalBytesAndSkipsR2() = runBlocking {
        val repository = FakeNoteRepository()
        val staging = MemoryStaging()
        val local = MemoryLocalStorage()
        val pendingBytes = byteArrayOf(1, 2, 3)
        val fileBytes = byteArrayOf(4, 5, 6)
        staging.blobs[GUEST_STAGING_OWNER to "pending-att"] = pendingBytes
        val filePath = fileStoragePath("/tmp/photo.jpg")
        local.files[filePath] = fileBytes

        val noteId = repository.insertNoteWithResult(
            Note(
                title = "With images",
                content = "",
                timestamp = 1L,
                color = 0,
                attachments = listOf(
                    Attachment(
                        id = "pending-att",
                        noteId = 0L,
                        storagePath = pendingStoragePath("pending-att"),
                        mimeType = "image/jpeg",
                    ),
                    Attachment(
                        id = "file-att",
                        noteId = 0L,
                        storagePath = filePath,
                        mimeType = "image/jpeg",
                    ),
                    Attachment(
                        id = "cloud-att",
                        noteId = 0L,
                        storagePath = "r2:owner/note/cloud-att",
                        mimeType = "image/jpeg",
                    ),
                ),
            ),
        )
        // Fix noteId on attachments for realism
        repository.updateNote(
            repository.getNoteById(noteId)!!.let { note ->
                note.copy(attachments = note.attachments.map { it.copy(noteId = noteId) })
            },
        )

        val outcome = transfer(repository, staging, local).exportBundle()
        assertEquals(2, outcome.attachmentsIncluded)
        assertEquals(1, outcome.attachmentsSkipped)
        assertTrue(BackupBundleCodec.looksLikeBundle("x.nlkbak", outcome.bytes.copyOf(2)))

        val parsed = BackupBundleCodec.parseBackupBundle(outcome.bytes)
        assertEquals(2, parsed.media.size)
        assertTrue(parsed.media["pending-att"]!!.bytes.contentEquals(pendingBytes))
        assertTrue(parsed.media["file-att"]!!.bytes.contentEquals(fileBytes))
    }

    @Test
    fun importRemintsAttachmentIdsOntoNewNotes() = runBlocking {
        val sourceRepo = FakeNoteRepository()
        val sourceStaging = MemoryStaging()
        val sourceLocal = MemoryLocalStorage()
        val image = byteArrayOf(9, 9, 9)
        sourceStaging.blobs[GUEST_STAGING_OWNER to "old-att"] = image
        val sourceNoteId = sourceRepo.insertNoteWithResult(
            Note(
                title = "Photo note",
                content = "body",
                timestamp = 1L,
                color = 0,
                attachments = listOf(
                    Attachment(
                        id = "old-att",
                        noteId = 0L,
                        storagePath = pendingStoragePath("old-att"),
                    ),
                ),
            ),
        )
        sourceRepo.updateNote(
            sourceRepo.getNoteById(sourceNoteId)!!.let { note ->
                note.copy(attachments = note.attachments.map { it.copy(noteId = sourceNoteId) })
            },
        )
        val archive = transfer(sourceRepo, sourceStaging, sourceLocal).exportBundle().bytes

        val destRepo = FakeNoteRepository()
        val destStaging = MemoryStaging()
        val destLocal = MemoryLocalStorage()
        val result = transfer(destRepo, destStaging, destLocal).importBundle(archive)
        assertIs<BackupImportResult.Success>(result)
        assertEquals(1, result.notesImported)
        assertEquals(1, result.attachmentsImported)
        assertEquals(0, result.attachmentsSkipped)

        val imported = destRepo.getAllNotesForBackup().single()
        assertEquals("Photo note", imported.title)
        assertEquals(1, imported.attachments.size)
        assertNotEquals("old-att", imported.attachments.single().id)
        assertTrue(imported.attachments.single().storagePath.startsWith("pending:"))
        val stagedBytes = destStaging.blobs[GUEST_STAGING_OWNER to imported.attachments.single().id]
        assertTrue(stagedBytes!!.contentEquals(image))

        // Second import produces another independent copy, not a collision.
        val second = transfer(destRepo, destStaging, destLocal).importBundle(archive)
        assertIs<BackupImportResult.Success>(second)
        assertEquals(2, destRepo.getAllNotesForBackup().size)
        val ids = destRepo.getAllNotesForBackup().flatMap { it.attachments }.map { it.id }
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
    }
}
