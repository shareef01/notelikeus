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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JVM port of `web/src/lib/backup/bundle/bundleTransfer.ts`.
 *
 * Export includes only bytes already on this device (pending staging or local `file:` paths) —
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
                "$skipped image${if (skipped == 1) "" else "s"} could not be included: the bytes " +
                    "are only in the cloud on this device. Open those notes while online first, then export again.",
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

    override suspend fun importBundle(archive: ByteArray): BackupImportResult {
        val parsed = try {
            BackupBundleCodec.parseBackupBundle(archive)
        } catch (error: BundleFormatException) {
            return BackupImportResult.InvalidFormat(error.message ?: "Invalid backup bundle")
        } catch (error: Exception) {
            AppLog.warn(TAG, "Parsing backup bundle failed", error)
            return BackupImportResult.Error(error)
        }

        val backupVersion = parsed.manifest.backup["version"]?.jsonPrimitive?.intOrNull
        if (backupVersion != null && backupVersion > NoteBackupExporter.BACKUP_VERSION) {
            return BackupImportResult.InvalidFormat("Unsupported backup version: $backupVersion")
        }

        val backupJson = json.encodeToString(JsonObject.serializer(), parsed.manifest.backup)
        val importResult = importer.importFromJson(backupJson)
        if (importResult !is BackupImportResult.Success) return importResult

        val newIdByOldId = importResult.newNoteIdByOldId
        val warnings = parsed.warnings.toMutableList()
        var attachmentsImported = 0
        var attachmentsSkipped = parsed.droppedAttachments
        val owner = ownerId()

        val attachmentsByNewNoteId = LinkedHashMap<Long, MutableList<Attachment>>()

        for (entry in parsed.manifest.attachments) {
            val oldNoteId = entry.noteId ?: continue
            val newNoteId = newIdByOldId[oldNoteId]
            val blob = parsed.media[entry.attachmentId]
            if (newNoteId == null || blob == null) {
                attachmentsSkipped++
                continue
            }

            val freshId = createAttachmentId()
            val mime = blob.mimeType ?: entry.mimeType ?: "image/jpeg"
            val staged = staging.stage(
                attachmentId = freshId,
                ownerId = owner,
                noteId = newNoteId,
                bytes = blob.bytes,
                mimeType = mime,
            )
            if (staged == null) {
                attachmentsSkipped++
                warnings.add("Could not save an image for an imported note")
                continue
            }

            attachmentsByNewNoteId.getOrPut(newNoteId) { ArrayList() }.add(
                Attachment(
                    id = freshId,
                    noteId = newNoteId,
                    storagePath = pendingStoragePath(freshId),
                    type = blob.type.ifBlank { "image" },
                    mimeType = mime,
                    sizeBytes = blob.bytes.size.toLong(),
                ),
            )
            attachmentsImported++
        }

        for ((newNoteId, attachments) in attachmentsByNewNoteId) {
            val note = repository.getNoteById(newNoteId) ?: continue
            repository.updateNote(note.copy(attachments = note.attachments + attachments))
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
