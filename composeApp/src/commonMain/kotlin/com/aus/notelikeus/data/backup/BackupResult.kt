package com.aus.notelikeus.data.backup

sealed class BackupExportResult {
    data class Success(val json: String) : BackupExportResult()
    data object WriteFailed : BackupExportResult()
    data class Error(val throwable: Throwable) : BackupExportResult()
}

sealed class BackupImportResult {
    /**
     * @param attachmentsSkipped images the file carried that this platform did not restore.
     *   Reported rather than ignored: a bundle exported from the web client lists its images in
     *   the manifest, and a user who imports it here needs to be told the pictures did not come
     *   with the text.
     */
    data class Success(
        val notesImported: Int,
        val labelsCreated: Int,
        val attachmentsSkipped: Int = 0,
    ) : BackupImportResult()
    data object ReadFailed : BackupImportResult()
    data class InvalidFormat(val message: String) : BackupImportResult()
    data class Error(val throwable: Throwable) : BackupImportResult()
}
