package com.aus.notelikeus.data.backup

sealed class BackupExportResult {
    data class Success(val json: String) : BackupExportResult()
    data object WriteFailed : BackupExportResult()
    data class Error(val throwable: Throwable) : BackupExportResult()
}

sealed class BackupImportResult {
    /**
     * @param attachmentsSkipped images the file carried that this platform did not restore.
     * @param attachmentsImported images staged successfully from a `.nlkbak` bundle (0 for JSON).
     * @param newNoteIdByOldId maps backup note ids to newly inserted Room ids (bundle remapping).
     */
    data class Success(
        val notesImported: Int,
        val labelsCreated: Int,
        val attachmentsSkipped: Int = 0,
        val attachmentsImported: Int = 0,
        val newNoteIdByOldId: Map<Long, Long> = emptyMap(),
        val warnings: List<String> = emptyList(),
    ) : BackupImportResult()
    data object ReadFailed : BackupImportResult()
    data class InvalidFormat(val message: String) : BackupImportResult()
    data class Error(val throwable: Throwable) : BackupImportResult()
}
