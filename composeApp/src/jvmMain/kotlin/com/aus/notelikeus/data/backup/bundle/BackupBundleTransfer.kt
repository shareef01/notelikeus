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
import com.aus.notelikeus.data.backup.BundleExportMode
import com.aus.notelikeus.data.backup.BundleExportOutcome
import com.aus.notelikeus.data.backup.NoteBackupExporter
import com.aus.notelikeus.data.backup.NoteBackupImporter
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.repository.NoteRepository
import com.aus.notelikeus.util.AppLog
import kotlinx.serialization.json.Json
import okio.Path.Companion.toOkioPath
import okio.buffer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JVM port of `web/src/lib/backup/bundle/bundleTransfer.ts`.
 *
 * A [BundleExportMode.Device] export includes only bytes already on this device (pending staging or
 * local `file:` paths) and never touches the network. A [BundleExportMode.Complete] export also
 * downloads images that live only in the cloud through [cloudAttachments]. Import remints attachment
 * ids onto freshly allocated note ids.
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
    private val cloudAttachments: CloudAttachmentSource? = null,
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

    /**
     * Streams the bundle into [sink], reading one image at a time (F-07), and closes it.
     *
     * Images are read when the codec asks for them, never collected up front: a library's images
     * can be far larger than the app's heap. In [BundleExportMode.Complete] an image that lives only
     * in the cloud is downloaded at that moment, one at a time, so the bound holds for those too. The
     * codec reads every image twice (to measure it, then to write it) and fails the export if the two
     * reads differ, so a download is repeated rather than parked in an unencrypted temp file.
     */
    override suspend fun exportBundle(sink: okio.Sink, mode: BundleExportMode): BundleExportOutcome {
        val notes = repository.getAllNotesForBackup()
        val document = json.parseToJsonElement(exporter.createJson()).jsonObject
        val download = if (mode == BundleExportMode.Complete) openCloudDownloader() else null
        val tally = ExportTally()
        val entries = notes.flatMap { note ->
            val noteId = note.id ?: return@flatMap emptyList()
            note.attachments.mapNotNull { attachment -> exportEntry(noteId, attachment, download, tally) }
        }

        val written = BackupBundleCodec.writeBackupBundle(
            output = sink.buffer().outputStream(),
            backupDocument = document,
            attachments = entries,
            origin = BundleOrigin(app = appName, appVersion = appVersion),
        )

        // The codec also drops an image it cannot store (a bad id, a repeat, over the size cap)
        // without reporting it, so what is missing is counted from what it wrote, not from what failed.
        val skipped = tally.total - written.included
        val rejected = (skipped - tally.cloudOnly - tally.unreadable - tally.downloadFailures.size).coerceAtLeast(0)
        return BundleExportOutcome(
            attachmentsIncluded = written.included,
            attachmentsSkipped = skipped,
            warnings = exportWarnings(tally, rejected),
            attachmentsTotal = tally.total,
            attachmentsFailed = tally.unreadable + tally.downloadFailures.size + rejected,
            mode = mode,
        )
    }

    /** What one export could not include, by reason. */
    private class ExportTally {
        var total = 0
        var cloudOnly = 0
        var unreadable = 0

        /** Keyed by attachment id, so the codec's second read of an image cannot count it twice. */
        val downloadFailures = LinkedHashMap<String, String>()
    }

    /**
     * The codec entry for one image, or null when it cannot be read at all in this mode: an image that
     * lives only in the cloud, in an export that does not download.
     */
    private fun exportEntry(
        noteId: Long,
        attachment: Attachment,
        download: CloudAttachmentDownloader?,
        tally: ExportTally,
    ): BundleAttachmentEntry? {
        tally.total++
        val local = isPendingAttachment(attachment.storagePath) || isFileAttachment(attachment.storagePath)
        if (!local && download == null) {
            tally.cloudOnly++
            return null
        }
        return BundleAttachmentEntry(
            noteId = noteId,
            attachmentId = attachment.id,
            type = attachment.type.ifBlank { "image" },
            mimeType = attachment.mimeType,
        ) {
            if (download == null || local) {
                readLocalAttachmentBytes(attachment)?.bytes.also { if (it == null) tally.unreadable++ }
            } else {
                when (val fetched = downloadCloudAttachment(download, noteId, attachment)) {
                    is CloudRead.Bytes -> fetched.bytes
                    is CloudRead.Failed -> {
                        tally.downloadFailures.putIfAbsent(attachment.id, fetched.reason)
                        null
                    }
                }
            }
        }
    }

    private fun exportWarnings(tally: ExportTally, rejected: Int): List<String> = buildList {
        if (tally.cloudOnly > 0) {
            add(
                "${tally.cloudOnly} image${if (tally.cloudOnly == 1) " was" else "s were"} not included because " +
                    "${if (tally.cloudOnly == 1) "its bytes are" else "their bytes are"} " +
                    "only available in cloud storage on this device.",
            )
        }
        if (tally.unreadable > 0) {
            add(
                "${tally.unreadable} image${if (tally.unreadable == 1) " was" else "s were"} skipped because " +
                    "local storage could not be read.",
            )
        }
        val failures = tally.downloadFailures
        if (failures.isNotEmpty()) {
            add(
                "${failures.size} image${if (failures.size == 1) "" else "s"} could not be " +
                    "downloaded: ${failures.values.distinct().joinToString("; ")}.",
            )
        }
        if (rejected > 0) {
            add("$rejected image${if (rejected == 1) " was" else "s were"} skipped because a backup cannot store them.")
        }
    }

    /** The downloader for this export, refused when it belongs to an account other than the one exporting. */
    private suspend fun openCloudDownloader(): CloudAttachmentDownloader {
        val source = cloudAttachments ?: error("A complete backup needs a connection to cloud storage.")
        val (cloudOwner, downloader) = source.open()
        check(cloudOwner == ownerIdProvider()) {
            "The signed-in account changed before the backup started; nothing was exported."
        }
        return downloader
    }

    private sealed interface CloudRead {
        class Bytes(val bytes: ByteArray) : CloudRead
        class Failed(val reason: String) : CloudRead
    }

    /**
     * Downloads one cloud-only image and checks it is the one the note references.
     *
     * Identity comes from the request: the Worker derives the object key from the signed-in user and
     * this note and attachment id, and answers 404 for anything else. What can still go wrong is the
     * body, so it is held to the size the server recorded at upload. One more attempt follows a failure;
     * rate limiting is already retried by the transport.
     */
    private suspend fun downloadCloudAttachment(
        download: CloudAttachmentDownloader,
        noteId: Long,
        attachment: Attachment,
    ): CloudRead {
        var lastFailure = "the download failed"
        repeat(CLOUD_DOWNLOAD_ATTEMPTS) {
            val bytes = try {
                download.download(noteId.toString(), attachment.id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.warn(TAG, "Downloading an image for a complete backup failed", error)
                lastFailure = "the download failed"
                return@repeat
            }
            val expected = attachment.sizeBytes
            when {
                bytes.isEmpty() -> return CloudRead.Failed("the server returned an empty file")
                expected != null && expected > 0 && bytes.size.toLong() != expected ->
                    lastFailure = "a download was ${bytes.size} bytes, expected $expected"
                else -> return CloudRead.Bytes(bytes)
            }
        }
        return CloudRead.Failed(lastFailure)
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
        // Owner-only (0600 on POSIX), unlike File.createTempFile: the archive holds the user's notes
        // and images, and the system temp directory is shared with other local users.
        val file = java.nio.file.Files
            .createTempFile("nlkbak-import", BackupBundleLimits.BUNDLE_FILE_EXTENSION)
            .toFile()
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

        /** The first download and one retry. */
        private const val CLOUD_DOWNLOAD_ATTEMPTS = 2
    }
}
