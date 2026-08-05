package com.aus.notelikeus.ui.main

sealed class CloudSyncEvent {
    data object SignedIn : CloudSyncEvent()
    data class SignedOut(val cloudDataDeleted: Boolean = false) : CloudSyncEvent()
    data class Uploaded(val noteCount: Int) : CloudSyncEvent()
    data class Downloaded(val noteCount: Int) : CloudSyncEvent()
    data class Failure(val message: String) : CloudSyncEvent()
    data object SignInRequired : CloudSyncEvent()
}
