package com.aus.notelikeus.data.backup.bundle

import com.aus.notelikeus.data.attachments.AttachmentLocalStorage
import com.aus.notelikeus.data.attachments.AttachmentStagingStore
import com.aus.notelikeus.data.attachments.GUEST_STAGING_OWNER
import com.aus.notelikeus.data.attachments.createAttachmentId
import com.aus.notelikeus.data.attachments.isFileAttachment
import com.aus.notelikeus.data.attachments.isPendingAttachment
import com.aus.notelikeus.data.attachments.pendingStoragePath
import com.aus.notelikeus.data.backup.BackupBundleOperations
import com.aus.notelikeus.data.backup.BackupImportResult
import com.aus.notelikeus.data.backup.BundleExportOutcome
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.util.AppLog
import kotlinx.serialization.json.Json
import okio.Path.Companion.toOkioPath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JVM port of `web/src/lib/backup/bundle/bundleTransfer.ts`.
 *
 * Export includes only bytes already on this device (pending staging or local `file:` paths) ÔÇö
 * never fetches R2. Import remints attachment ids onto freshly allocated note ids.
 */
class BackupBundleTransfer(
    private val repository: NoteRepository,
    private val exporter: NoteBackupExporter,
    private val importer: NoteBackupImporter,
    private val staging: AttachmentStagingStore,
    private val localStorage: AttachmentLocalStorage,
    private val ownerIdProvider: () -> String?,
    private val appName: String = "Notelikeus",
    private val appVersion: String,
) : BackupBundleOperations {

    private val json = Json { ignoreUnknownKeys = true }

    private fun ownerId(): String = ownerIdProvider() ?: GUEST_STAGING_OWNER

    /** What [stageBundleAttachments] produced: the staged rows, and the counts it moved. */
    private class StagedBundleAttachments(
        val byNoteId: Map<Long, List<Attachment>>,
        val imported: Int,
        val skipped: Int,
    )

    /**
     * Stages every image the archive carries, keyed by the note id it was reminted onto.
     *
     * One image at a time: each is read, verified, staged and released before the next is read, so
     * the heap holds at most one image however large the bundle is (F-07).
     *
     * Split out of [importBundle]: it is a self-contained pass over the manifest with its own
     * counters and warnings, and keeping it inline pushed that method past the project's length and
     * complexity limits.
     */
    private suspend fun stageBundleAttachments(
        bundle: OpenedBackupBundle,
        newIdByOldId: Map<Long, Long>,
        owner: String,
        warnings: MutableList<String>,
        alreadySkipped: Int,
    ): StagedBundleAttachments {
        val byNoteId = LinkedHashMap<Long, MutableList<Attachment>>()
        var skipped = alreadySkipped
        var imported = 0

        for (entry in bundle.manifest.attachments) {
            val oldNoteId = entry.noteId ?: continue
            val newNoteId = newIdByOldId[oldNoteId]
            if (newNoteId == null) {
                skipped++
                continue
            }
            val blob = when (val read = bundle.readMedia(entry)) {
                is MediaRead.Loaded -> read.media
                is MediaRead.Dropped -> {
                    skipped++
                    warnings.add(read.warning)
                    continue
                }
            }

            val freshId = createAttachmentId()
            val mime = blob.mimeType ?: "image/jpeg"
            val staged = staging.stage(
                attachmentId = freshId,
                ownerId = owner,
                noteId = newNoteId,
                bytes = blob.bytes,
                mimeType = mime,
            )
            if (staged == null) {
                skipped++
                warnings.add("Could not save an image for an imported note")
                continue
            }

            byNoteId.getOrPut(newNoteId) { ArrayList() }.add(
                Attachment(
                    id = freshId,
                    noteId = newNoteId,
                    storagePath = pendingStoragePath(freshId),
                    type = blob.type.ifBlank { "image" },
                    mimeType = mime,
                    sizeBytes = blob.bytes.size.toLong(),
                ),
            )
            imported++
        }

        return StagedBundleAttachments(byNoteId = byNoteId, imported = imported, skipped = skipped)
    }

    /**
     * Writes the staged attachment rows onto their notes, under the import's own origin token.
     *
     * Returns false when the dataset that asked for the import is gone — account-owned local state
     * plus an upload, so a boundary that landed while the archive was being unpacked refuses it
     * rather than rewriting whatever the replacement dataset holds at that id.
     */
    private suspend fun attachStagedAttachments(
        byNoteId: Map<Long, List<Attachment>>,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): Boolean {
        for ((newNoteId, attachments) in byNoteId) {
            val note = repository.getNoteById(newNoteId) ?: continue
            val attached = repository.updateNote(
                note.copy(attachments = note.attachments + attachments),
                commitToken,
            )
            if (attached is com.aus.notelikeus.domain.repository.LocalCommitResult.StaleGeneration) {
                return false
            }
        }
        return true
    }

    override fun looksLikeBundle(fileName: String?, head: ByteArray): Boolean =
        BackupBundleCodec.looksLikeBundle(fileName, head)

    override fun bundleFileName(): String {
        val day = java.time.LocalDate.now().toString()
        return "notelikeus_backup_${day}${BackupBundleLimits.BUNDLE_FILE_EXTENSION}"
    }

    override suspend fun exportBundle(): BundleExportOutcome {
        val notes = repository.getAllNotesForBackup()
        val documentJson = exporter.createJson()
        val document = json.parseToJsonElement(documentJson).jsonObject

        val sources = ArrayList<BundleAttachmentSource>()
        var skipped = 0
        val warnings = ArrayList<String>()

        for (note in notes) {
            val noteId = note.id ?: continue
            for (attachment in note.attachments) {
                val local = readLocalAttachmentBytes(attachment)
                if (local == null) {
                    skipped++
                    continue
                }
                sources.add(
                    BundleAttachmentSource(
                        noteId = noteId,
                        attachmentId = attachment.id,
                        type = attachment.type.ifBlank { "image" },
                        mimeType = local.mimeType ?: attachment.mimeType,
                        bytes = local.bytes,
                    ),
                )
            }
        }

        if (skipped > 0) {
            warnings.add(
                "$skipped image${if (skipped == 1) " was" else "s were"} not included because " +
                    "${if (skipped == 1) "its bytes are" else "their bytes are"} only available in cloud storage on this device.",
            )
        }

        val bytes = BackupBundleCodec.buildBackupBundle(
            backupDocument = document,
            attachments = sources,
            app = appName,
            appVersion = appVersion,
        )
        return BundleExportOutcome(
            bytes = bytes,
            attachmentsIncluded = sources.size,
            attachmentsSkipped = skipped,
            warnings = warnings,
        )
    }

    override suspend fun importBundle(
        archive: okio.Path,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): BackupImportResult {
        val bundle = try {
            BackupBundleCodec.openBackupBundle(archive.toFile())
        } catch (error: BundleFormatException) {
            return BackupImportResult.InvalidFormat(error.message ?: "Invalid backup bundle")
        } catch (error: Exception) {
            AppLog.warn(TAG, "Opening backup bundle failed", error)
            return BackupImportResult.Error(error)
        }
        return bundle.use { importOpened(it, commitToken) }
    }

    /** For callers that already hold the bytes; spills them to a temp file and imports that. */
    internal suspend fun importBundle(
        archive: ByteArray,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): BackupImportResult {
        val file = java.io.File.createTempFile("nlkbak-import", BackupBundleLimits.BUNDLE_FILE_EXTENSION)
        return try {
            file.writeBytes(archive)
            importBundle(file.toOkioPath(), commitToken)
        } finally {
            file.delete()
        }
    }

    private suspend fun importOpened(
        bundle: OpenedBackupBundle,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): BackupImportResult {
        val backupVersion = bundle.manifest.backup["version"]?.jsonPrimitive?.intOrNull
        if (backupVersion != null && backupVersion > NoteBackupExporter.BACKUP_VERSION) {
            return BackupImportResult.InvalidFormat("Unsupported backup version: $backupVersion")
        }

        val backupJson = json.encodeToString(JsonObject.serializer(), bundle.manifest.backup)
        val importResult = importer.importFromJson(backupJson, commitToken)
        if (importResult !is BackupImportResult.Success) return importResult

        val newIdByOldId = importResult.newNoteIdByOldId
        val warnings = bundle.warnings.toMutableList()
        var attachmentsImported = 0
        var attachmentsSkipped = bundle.droppedAttachments
        val owner = ownerId()

        val staged = stageBundleAttachments(
            bundle = bundle,
            newIdByOldId = newIdByOldId,
            owner = owner,
            warnings = warnings,
            alreadySkipped = attachmentsSkipped,
        )
        attachmentsSkipped = staged.skipped
        attachmentsImported = staged.imported

        if (
            !attachStagedAttachments(byNoteId = staged.byNoteId, commitToken = commitToken)
        ) {
            // The dataset this import belonged to is gone: its bytes are staged but unreferenced,
            // and its rows were wiped with it. Reported as superseded, never as Success.
            return BackupImportResult.Superseded(notesImported = importResult.notesImported)
        }

        if (attachmentsSkipped > 0) {
            warnings.add(
                "$attachmentsSkipped image${if (attachmentsSkipped == 1) "" else "s"} could not be restored. " +
                    "The notes they belonged to were imported.",
            )
        }

        return BackupImportResult.Success(
            notesImported = importResult.notesImported,
            labelsCreated = importResult.labelsCreated,
            attachmentsSkipped = attachmentsSkipped,
            attachmentsImported = attachmentsImported,
            newNoteIdByOldId = newIdByOldId,
            warnings = warnings,
        )
    }

    private suspend fun readLocalAttachmentBytes(
        attachment: com.aus.notelikeus.domain.model.Attachment,
    ): LocalBytes? {
        return try {
            when {
                isPendingAttachment(attachment.storagePath) -> {
                    val pendingId = attachment.storagePath.removePrefix(
                        com.aus.notelikeus.data.attachments.ATTACHMENT_PENDING_PREFIX,
                    )
                    val bytes = staging.readBytes(pendingId, ownerId()) ?: return null
                    LocalBytes(bytes = bytes, mimeType = attachment.mimeType)
                }
                isFileAttachment(attachment.storagePath) -> {
                    val bytes = localStorage.readBytes(attachment.storagePath) ?: return null
                    LocalBytes(bytes = bytes, mimeType = attachment.mimeType)
                }
                else -> null
            }
        } catch (error: Exception) {
            AppLog.warn(TAG, "Reading local attachment for export failed", error)
            null
        }
    }

    private data class LocalBytes(val bytes: ByteArray, val mimeType: String?)

    companion object {
        private const val TAG = "BackupBundle"
    }
}
