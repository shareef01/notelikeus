package com.aus.notelikeus.data.backup

sealed class BackupExportResult {
    data object Success : BackupExportResult()
    data object WriteFailed : BackupExportResult()
    data class Error(val throwable: Throwable) : BackupExportResult()
}

sealed class BackupImportResult {
    data class Success(val notesImported: Int, val labelsCreated: Int) : BackupImportResult()
    data object ReadFailed : BackupImportResult()
    data class InvalidFormat(val message: String) : BackupImportResult()
    data class Error(val throwable: Throwable) : BackupImportResult()
}
