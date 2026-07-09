package com.aus.notelikeus.ui.main

enum class CloudSyncStatus {
    Unknown,
    Connected,
    Offline,
    Syncing,
    Synced,
    Error
}

sealed class CloudSyncEvent {
    data class Uploaded(val noteCount: Int) : CloudSyncEvent()
    data class Downloaded(val noteCount: Int) : CloudSyncEvent()
    data object SignedIn : CloudSyncEvent()
    data class SignedOut(val cloudDataDeleted: Boolean = false) : CloudSyncEvent()
    data object SignInRequired : CloudSyncEvent()
    data class Failure(val message: String) : CloudSyncEvent()
}
