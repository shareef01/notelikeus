package com.aus.notelikeus.data.backup

sealed class BackupExportResult {
    /** [lockedNoteCount] drives the plaintext warning — the export is not encrypted. */
    data class Success(val lockedNoteCount: Int) : BackupExportResult()
    data object WriteFailed : BackupExportResult()
    data class Error(val throwable: Throwable) : BackupExportResult()
}

sealed class BackupImportResult {
    data class Success(val notesImported: Int, val labelsCreated: Int) : BackupImportResult()
    data object ReadFailed : BackupImportResult()
    data class InvalidFormat(val message: String) : BackupImportResult()
    data class Error(val throwable: Throwable) : BackupImportResult()
}
