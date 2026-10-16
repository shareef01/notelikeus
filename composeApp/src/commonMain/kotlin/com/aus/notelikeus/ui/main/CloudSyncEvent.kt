package com.aus.notelikeus.ui.main

sealed class CloudSyncEvent {
    data object SignedIn : CloudSyncEvent()
    data class SignedOut(val cloudDataDeleted: Boolean = false) : CloudSyncEvent()
    data class Uploaded(val noteCount: Int) : CloudSyncEvent()
    /**
     * @param deletedNotesBack notes this device deleted that the download restored because another
     *   device changed them since (AU-07); the user is told, so the delete does not look lost.
     * @param editsKeptFromDeleted notes deleted in the cloud whose unsynced words this device kept
     *   as a copy (see NoteSyncEngine.takeEditsKeptFromDeletedNotes), so the copy is explained.
     */
    data class Downloaded(
        val noteCount: Int,
        val deletedNotesBack: Int = 0,
        val editsKeptFromDeleted: Int = 0,
    ) : CloudSyncEvent()
    data class Failure(val message: String) : CloudSyncEvent()
    data object SignInRequired : CloudSyncEvent()
}
