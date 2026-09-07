package com.aus.notelikeus.ui.main

/**
 * The outcome of a backup export or import, for the UI to report.
 *
 * Both actions used to finish in silence on every platform: the strings for reporting them have
 * existed since the feature was added but were referenced nowhere, so a user who picked a file
 * had no way to tell whether anything happened, how much came across, or why nothing did.
 */
sealed class BackupTransferEvent {
    data object Exported : BackupTransferEvent()
    data object ExportFailed : BackupTransferEvent()
    data class Imported(val notesImported: Int) : BackupTransferEvent()
    data object ImportFailed : BackupTransferEvent()

    /** The file parsed but was not a backup this build accepts; [message] says why. */
    data class ImportRejected(val message: String) : BackupTransferEvent()
}
