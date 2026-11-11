package com.aus.notelikeus.ui.main

import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

sealed class CloudSyncEvent {
    data object SignedIn : CloudSyncEvent()
    data class SignedOut(val cloudDataDeleted: Boolean = false) : CloudSyncEvent()

    /** @param elsewhere deleted from another device, which this one has just found out. */
    data class AccountDeleted(val elsewhere: Boolean = false) : CloudSyncEvent()
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
    /**
     * @param message text from the server or the sync layer, shown as it is.
     * @param messageRes the app's own wording, looked up in the user's language when shown. Set
     *   instead of [message] for every failure the app describes itself (NL-12).
     */
    data class Failure(
        val message: String,
        val messageRes: StringResource? = null,
    ) : CloudSyncEvent() {
        constructor(messageRes: StringResource) : this(message = "", messageRes = messageRes)

        /** The text to show: the resource in the current language, else [message]. */
        suspend fun text(): String = messageRes?.let { getString(it) } ?: message
    }
    data object SignInRequired : CloudSyncEvent()
}
