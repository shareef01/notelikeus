package com.aus.notelikeus.data.backup

/**
 * Platform-facing API for `.nlkbak` export/import.
 *
 * Implemented in jvmMain ([com.aus.notelikeus.data.backup.bundle.BackupBundleTransfer]);
 * commonMain only sees this interface so [com.aus.notelikeus.ui.main.MainViewModel] can call it.
 */
interface BackupBundleOperations {
    /**
     * Streams a bundle of every note and every image held on this device into [sink], and closes
     * it. Streamed rather than returned as bytes, so a library's images never have to fit in memory
     * at once (F-07).
     */
    suspend fun exportBundle(
        sink: okio.Sink,
        mode: BundleExportMode = BundleExportMode.Device,
    ): BundleExportOutcome
    /**
     * Imports the bundle file at [archive] into the dataset [commitToken] was captured in — see
     * [NoteBackupImporter.importFromJson], whose rules this follows for the bundle's own notes and
     * for the attachment re-attachment that follows them.
     *
     * A file rather than bytes, so the bundle is never held in memory whole (F-07): implementations
     * read its manifest and then one image at a time.
     */
    suspend fun importBundle(
        archive: okio.Path,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): BackupImportResult

    fun looksLikeBundle(fileName: String?, head: ByteArray): Boolean
    fun bundleFileName(): String
}

/**
 * [Device] is the offline backup: only bytes already on this device, never a network request.
 * [Complete] also downloads images that live only in the cloud, through the authenticated Worker GET,
 * so the archive can hold the whole library (AUD-001). Both write the same v4 layout.
 */
enum class BundleExportMode { Device, Complete }

data class BundleExportOutcome(
    val attachmentsIncluded: Int,
    /** Not included for any reason: [attachmentsTotal] minus [attachmentsIncluded]. */
    val attachmentsSkipped: Int,
    val warnings: List<String>,
    /** Every image the exported notes reference. */
    val attachmentsTotal: Int = attachmentsIncluded + attachmentsSkipped,
    /** The part of [attachmentsSkipped] that was tried and failed: unreadable, a failed download, or rejected. */
    val attachmentsFailed: Int = 0,
    val mode: BundleExportMode = BundleExportMode.Device,
) {
    /**
     * True only when every referenced image is in the archive. Nothing else may call a bundle complete:
     * the delete-account dialog relies on this to decide whether the user still needs a warning.
     */
    val complete: Boolean get() = attachmentsIncluded == attachmentsTotal
}

object NoopBackupBundleOperations : BackupBundleOperations {
    override suspend fun exportBundle(sink: okio.Sink, mode: BundleExportMode): BundleExportOutcome =
        error("Bundle export is not available on this platform")

    override suspend fun importBundle(
        archive: okio.Path,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): BackupImportResult =
        BackupImportResult.InvalidFormat("Bundle import is not available on this platform")

    override fun looksLikeBundle(fileName: String?, head: ByteArray): Boolean = false

    override fun bundleFileName(): String = "notelikeus_backup.nlkbak"
}
