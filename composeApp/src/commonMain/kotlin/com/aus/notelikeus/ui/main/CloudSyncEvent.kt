package com.aus.notelikeus.ui.main

sealed class CloudSyncEvent {
    object SignedIn : CloudSyncEvent()
    data class SignedOut(val cloudDataDeleted: Boolean) : CloudSyncEvent()
    data class Uploaded(val count: Int) : CloudSyncEvent()
    data class Downloaded(val count: Int) : CloudSyncEvent()
    data class Failure(val message: String) : CloudSyncEvent()
    object SignInRequired : CloudSyncEvent()
}
