package com.aus.notelikeus.data.sync

import com.aus.notelikeus.ui.main.CloudSyncEvent
import com.aus.notelikeus.ui.main.CloudSyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A sync that never returns must not strand the UI.
 *
 * [runTimedSync] flips the status to [CloudSyncStatus.Syncing] and only leaves it on the result, but
 * nothing in the transport bounded how long that could take. Observed on a real device: with the
 * network off, the status stayed Syncing indefinitely, and because ProfileSheet gates its sync
 * controls on `cloudSyncStatus != Syncing`, "Sync to cloud" and "Restore from cloud" were disabled
 * for as long as it hung — so the one screen that could retry had no working button on it. Only
 * reconnecting released it.
 *
 * Generous on purpose: a first full sync of a large library is slow, and cutting a working sync
 * short would be worse than the hang. This is the backstop, not a deadline.
 */
const val SYNC_TIMEOUT_MS = 90_000L

const val SYNC_TIMED_OUT_MESSAGE =
    "Sync took too long and was stopped. Check your connection and try again."

/**
 * Runs [block] as a status-reporting sync, shared by every platform's `SyncManager`.
 *
 * Reports what actually happened: the platforms used to discard the [Result] and set
 * [CloudSyncStatus.Synced] unconditionally, which is why a completely non-functional cloud path
 * still displayed as healthy.
 *
 * [successEvent] supplies the event to publish on success (null publishes none), [describeError]
 * turns a platform-specific failure into user-facing text, and [onFailure] runs after the failure
 * has been published for platform-specific cleanup such as dropping a session the transport just
 * invalidated.
 */
suspend fun runTimedSync(
    status: MutableStateFlow<CloudSyncStatus>,
    pendingEvent: MutableStateFlow<CloudSyncEvent?>,
    describeError: (Throwable) -> String,
    successEvent: (Int) -> CloudSyncEvent? = { null },
    onFailure: (Throwable) -> Unit = {},
    block: suspend () -> Result<Int>
) {
    status.value = CloudSyncStatus.Syncing
    val result = withTimeoutOrNull(SYNC_TIMEOUT_MS) { block() }
    if (result == null) {
        status.value = CloudSyncStatus.Error
        pendingEvent.value = CloudSyncEvent.Failure(SYNC_TIMED_OUT_MESSAGE)
        return
    }
    result
        .onSuccess { count ->
            status.value = CloudSyncStatus.Synced
            successEvent(count)?.let { pendingEvent.value = it }
        }
        .onFailure { error ->
            status.value = CloudSyncStatus.Error
            pendingEvent.value = CloudSyncEvent.Failure(describeError(error))
            onFailure(error)
        }
}
