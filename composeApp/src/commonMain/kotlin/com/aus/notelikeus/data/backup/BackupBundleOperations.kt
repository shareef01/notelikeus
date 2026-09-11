package com.aus.notelikeus.data.backup

/**
 * Platform-facing API for `.nlkbak` export/import.
 *
 * Implemented in jvmMain ([com.aus.notelikeus.data.backup.bundle.BackupBundleTransfer]);
 * commonMain only sees this interface so [com.aus.notelikeus.ui.main.MainViewModel] can call it.
 */
interface BackupBundleOperations {
    suspend fun exportBundle(): BundleExportOutcome
    suspend fun importBundle(archive: ByteArray): BackupImportResult

    fun looksLikeBundle(fileName: String?, head: ByteArray): Boolean
    fun bundleFileName(): String
}

data class BundleExportOutcome(
    val bytes: ByteArray,
    val attachmentsIncluded: Int,
    val attachmentsSkipped: Int,
    val warnings: List<String>,
)

object NoopBackupBundleOperations : BackupBundleOperations {
    override suspend fun exportBundle(): BundleExportOutcome =
        error("Bundle export is not available on this platform")

    override suspend fun importBundle(archive: ByteArray): BackupImportResult =
        BackupImportResult.InvalidFormat("Bundle import is not available on this platform")

    override fun looksLikeBundle(fileName: String?, head: ByteArray): Boolean = false

    override fun bundleFileName(): String = "notelikeus_backup.nlkbak"
}
