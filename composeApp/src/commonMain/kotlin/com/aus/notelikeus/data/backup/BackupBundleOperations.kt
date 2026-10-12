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
    suspend fun exportBundle(sink: okio.Sink): BundleExportOutcome
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

data class BundleExportOutcome(
    val attachmentsIncluded: Int,
    val attachmentsSkipped: Int,
    val warnings: List<String>,
)

object NoopBackupBundleOperations : BackupBundleOperations {
    override suspend fun exportBundle(sink: okio.Sink): BundleExportOutcome =
        error("Bundle export is not available on this platform")

    override suspend fun importBundle(
        archive: okio.Path,
        commitToken: com.aus.notelikeus.domain.repository.LocalCommitToken,
    ): BackupImportResult =
        BackupImportResult.InvalidFormat("Bundle import is not available on this platform")

    override fun looksLikeBundle(fileName: String?, head: ByteArray): Boolean = false

    override fun bundleFileName(): String = "notelikeus_backup.nlkbak"
}
