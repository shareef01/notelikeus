package com.aus.notelikeus.data.backup.bundle

import com.aus.notelikeus.data.attachments.AttachmentLocalStorage
import com.aus.notelikeus.data.attachments.AttachmentStagingStore
import com.aus.notelikeus.data.attachments.StagedAttachment
import com.aus.notelikeus.data.attachments.pendingStoragePath
import com.aus.notelikeus.data.backup.BackupImportResult
import com.aus.notelikeus.data.backup.BundleExportMode
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.data.sync.FakeNoteRepository
import com.aus.notelikeus.data.sync.actionToken
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.main.BackupTransferEvent
import com.aus.notelikeus.ui.main.isDeleteAccountArmed
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Test

/**
 * AUD-001 on Android and Windows. Once an image has synced, its bytes live only in R2, so an export of
 * local bytes alone left every synced image out of the backup the delete-account dialog offers. The
 * complete export downloads them; these hold it to never calling a partial archive complete.
 */
class CompleteBundleExportTest {

    private val owner = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

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
            return StagedAttachment(attachmentId, ownerId, noteId, mimeType, bytes.size.toLong(), 1L)
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

    private class NoLocalFiles : AttachmentLocalStorage {
        override fun persistImageBytes(bytes: ByteArray, extension: String): String? = null
        override fun readBytes(storagePath: String): ByteArray? = null
        override fun exists(storagePath: String): Boolean = false
        override fun deleteIfLocal(storagePath: String) = Unit
    }

    /** The cloud as the Worker serves it, recording every request. */
    private class FakeCloud(
        val objects: Map<String, ByteArray>,
        val cloudOwner: String,
    ) : CloudAttachmentSource {
        val requests = mutableListOf<String>()
        override suspend fun open(): Pair<String, CloudAttachmentDownloader> =
            cloudOwner to CloudAttachmentDownloader { noteId, attachmentId ->
                requests += "$noteId/$attachmentId"
                objects["$noteId/$attachmentId"]?.copyOf() ?: error("attachment download failed (404)")
            }
    }

    private fun image(seed: Int, size: Int = 4096) = ByteArray(size) { ((it * 31 + seed) and 0xff).toByte() }

    private fun transfer(
        repository: FakeNoteRepository,
        staging: MemoryStaging,
        cloud: CloudAttachmentSource?,
        ownerId: String? = owner,
    ) = BackupBundleTransfer(
        repository = repository,
        exporter = NoteBackupExporter(repository, "Notelikeus", "test"),
        importer = NoteBackupImporter(repository),
        staging = staging,
        localStorage = NoLocalFiles(),
        ownerIdProvider = { ownerId },
        appVersion = "test",
        cloudAttachments = cloud,
    )

    /** A note whose attachments carry its real id, the way they are stored. */
    private suspend fun insertNote(repository: FakeNoteRepository, attachments: (Long) -> List<Attachment>): Long {
        val id = repository.insertNoteWithResult(
            Note(title = "Photos", content = "", timestamp = 1L, color = 0, attachments = emptyList()),
        )
        repository.updateNote(repository.getNoteById(id)!!.copy(attachments = attachments(id)))
        return id
    }

    private fun cloudAttachment(noteId: Long, id: String, bytes: ByteArray) = Attachment(
        id = id,
        noteId = noteId,
        storagePath = "r2:owners/x/notes/$noteId/$id",
        mimeType = "image/png",
        sizeBytes = bytes.size.toLong(),
    )

    @Test
    fun deviceExportLeavesSyncedImagesOutAndSaysItIsIncomplete() = runBlocking {
        val repository = FakeNoteRepository()
        val bytes = image(1)
        insertNote(repository) { listOf(cloudAttachment(it, "cloud-1", bytes)) }
        val cloud = FakeCloud(emptyMap(), owner)

        val outcome = transfer(repository, MemoryStaging(), cloud).exportBundle(Buffer(), BundleExportMode.Device)

        assertFalse(outcome.complete)
        assertEquals(1, outcome.attachmentsTotal)
        assertEquals(0, outcome.attachmentsIncluded)
        assertTrue(cloud.requests.isEmpty(), "the offline export must not touch the network")
    }

    @Test
    fun completeExportCarriesSyncedImagesThroughARestoreIntoACleanDevice() = runBlocking {
        val repository = FakeNoteRepository()
        val staging = MemoryStaging()
        val synced = listOf(image(1), image(2), image(3, 9000))
        val local = image(4)
        staging.blobs[owner to "local-1"] = local
        val noteId = insertNote(repository) { id ->
            listOf(
                cloudAttachment(id, "cloud-1", synced[0]),
                cloudAttachment(id, "cloud-2", synced[1]),
                cloudAttachment(id, "cloud-3", synced[2]),
                Attachment(id = "local-1", noteId = id, storagePath = pendingStoragePath("local-1"), mimeType = "image/png"),
            )
        }
        val cloud = FakeCloud(
            mapOf("$noteId/cloud-1" to synced[0], "$noteId/cloud-2" to synced[1], "$noteId/cloud-3" to synced[2]),
            owner,
        )

        val buffer = Buffer()
        val outcome = transfer(repository, staging, cloud).exportBundle(buffer, BundleExportMode.Complete)

        assertTrue(outcome.complete)
        assertEquals(4, outcome.attachmentsTotal)
        assertEquals(4, outcome.attachmentsIncluded)
        assertEquals(0, outcome.attachmentsFailed)
        // The codec reads each image twice, so each cloud image is downloaded twice and compared.
        assertEquals(6, cloud.requests.size)

        // A clean device: an empty repository and staging, signed out.
        val archive = buffer.readByteArray()
        val restoredRepository = FakeNoteRepository()
        val restoredStaging = MemoryStaging()
        val result = transfer(restoredRepository, restoredStaging, null, ownerId = null)
            .importBundle(archive, actionToken())
        assertIs<BackupImportResult.Success>(result)
        assertEquals(4, result.attachmentsImported)
        assertEquals(0, result.attachmentsSkipped)
        val restoredBytes = restoredStaging.blobs.values.map { it.toList() }.toSet()
        assertEquals((synced + local).map { it.toList() }.toSet(), restoredBytes)
    }

    @Test
    fun aFailedDownloadMakesTheExportIncompleteAndIsRetriedOnce() = runBlocking {
        val repository = FakeNoteRepository()
        val bytes = image(1)
        val noteId = insertNote(repository) {
            listOf(cloudAttachment(it, "cloud-1", bytes), cloudAttachment(it, "gone", image(2)))
        }
        val cloud = FakeCloud(mapOf("$noteId/cloud-1" to bytes), owner)

        val outcome = transfer(repository, MemoryStaging(), cloud).exportBundle(Buffer(), BundleExportMode.Complete)

        assertFalse(outcome.complete)
        assertEquals(1, outcome.attachmentsIncluded)
        assertEquals(1, outcome.attachmentsFailed)
        assertEquals(2, cloud.requests.count { it == "$noteId/gone" })
        assertTrue(outcome.warnings.any { "could not be downloaded" in it })
    }

    @Test
    fun aTruncatedDownloadIsNotArchived() = runBlocking {
        val repository = FakeNoteRepository()
        val full = image(1, 8000)
        val noteId = insertNote(repository) { listOf(cloudAttachment(it, "cloud-1", full)) }
        val cloud = FakeCloud(mapOf("$noteId/cloud-1" to full.copyOf(5000)), owner)

        val outcome = transfer(repository, MemoryStaging(), cloud).exportBundle(Buffer(), BundleExportMode.Complete)

        assertFalse(outcome.complete)
        assertEquals(0, outcome.attachmentsIncluded)
        assertTrue(outcome.warnings.any { "expected 8000" in it })
    }

    @Test
    fun anImageTheArchiveCannotStoreIsCountedAsMissing() = runBlocking {
        val repository = FakeNoteRepository()
        val huge = ByteArray((BackupBundleLimits.MAX_BUNDLE_ATTACHMENT_BYTES + 1).toInt())
        val noteId = insertNote(repository) { listOf(cloudAttachment(it, "cloud-1", huge)) }
        val cloud = FakeCloud(mapOf("$noteId/cloud-1" to huge), owner)

        val outcome = transfer(repository, MemoryStaging(), cloud).exportBundle(Buffer(), BundleExportMode.Complete)

        assertFalse(outcome.complete)
        assertEquals(1, outcome.attachmentsSkipped)
    }

    @Test
    fun aCompleteExportRefusesACloudSessionForAnotherAccount() = runBlocking {
        val repository = FakeNoteRepository()
        insertNote(repository) { listOf(cloudAttachment(it, "cloud-1", image(1))) }
        val cloud = FakeCloud(emptyMap(), cloudOwner = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

        val failure = runCatching {
            transfer(repository, MemoryStaging(), cloud).exportBundle(Buffer(), BundleExportMode.Complete)
        }.exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        assertTrue(cloud.requests.isEmpty())
    }

    @Test
    fun deleteAccountNeedsTheLossAcceptedOnlyAfterAnIncompleteBackup() {
        val incomplete = BackupTransferEvent.BundleExported(attachmentsIncluded = 1, attachmentsSkipped = 3)
        val complete = BackupTransferEvent.BundleExported(attachmentsIncluded = 4, attachmentsSkipped = 0)

        assertTrue(isDeleteAccountArmed("DELETE", isDeleting = false, lastExport = null, acceptedLoss = false))
        assertTrue(isDeleteAccountArmed("DELETE", isDeleting = false, lastExport = complete, acceptedLoss = false))
        assertFalse(isDeleteAccountArmed("DELETE", isDeleting = false, lastExport = incomplete, acceptedLoss = false))
        assertTrue(isDeleteAccountArmed("DELETE", isDeleting = false, lastExport = incomplete, acceptedLoss = true))
        assertFalse(isDeleteAccountArmed("delete", isDeleting = false, lastExport = incomplete, acceptedLoss = true))
        assertFalse(isDeleteAccountArmed("DELETE", isDeleting = true, lastExport = null, acceptedLoss = false))
        assertFalse(incomplete.complete)
        assertTrue(complete.complete)
    }
}
