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
    data class BundleExported(
        val attachmentsIncluded: Int,
        val attachmentsSkipped: Int,
        /** Every image the exported notes reference. */
        val attachmentsTotal: Int = attachmentsIncluded + attachmentsSkipped,
    ) : BackupTransferEvent() {
        /** Only when every image is in the file. Nothing else may call a backup complete. */
        val complete: Boolean get() = attachmentsIncluded == attachmentsTotal

        constructor(outcome: com.aus.notelikeus.data.backup.BundleExportOutcome) : this(
            attachmentsIncluded = outcome.attachmentsIncluded,
            attachmentsSkipped = outcome.attachmentsSkipped,
            attachmentsTotal = outcome.attachmentsTotal,
        )
    }
    data object ExportFailed : BackupTransferEvent()
    data class Imported(
        val notesImported: Int,
        val attachmentsImported: Int = 0,
        val attachmentsSkipped: Int = 0,
    ) : BackupTransferEvent()
    data object ImportFailed : BackupTransferEvent()

    /** The file parsed but was not a backup this build accepts; [message] says why. */
    data class ImportRejected(val message: String) : BackupTransferEvent()
}
