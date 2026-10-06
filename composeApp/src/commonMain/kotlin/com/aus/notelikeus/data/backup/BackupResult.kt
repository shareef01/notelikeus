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
    /**
     * The import belonged to a dataset that was replaced while it ran.
     *
     * Not a failure of the file, and not a storage error: the user's session changed, so the rows the
     * import written were dropped by the isolation that won and the uploads it would have queued were
     * refused. It is reported separately so the UI can say "sign in again and import once more"
     * instead of telling the user their backup file could not be read.
     *
     * @param notesImported how many notes the superseded attempt had written before the boundary;
     * they are gone with the dataset, and the count is carried for diagnostics and for the message.
     */
    data class Superseded(val notesImported: Int) : BackupImportResult()
    data class InvalidFormat(val message: String) : BackupImportResult()
    data class Error(val throwable: Throwable) : BackupImportResult()
}
