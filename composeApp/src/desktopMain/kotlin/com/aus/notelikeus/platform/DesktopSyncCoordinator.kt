package com.aus.notelikeus.platform

import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.domain.platform.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Desktop implementation of [SyncCoordinator] that performs immediate uploads.
 * Since Desktop lacks a reliable WorkManager, we use a simple background scope
 * to trigger the shared [NoteSyncEngine] on every mutation.
 */
class DesktopSyncCoordinator(
    private val syncEngine: NoteSyncEngine
) : SyncCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun scheduleUpload(noteId: Long) {
        scope.launch {
            syncEngine.uploadNote(noteId)
        }
    }

    override fun scheduleDelete(noteId: Long) {
        scope.launch {
            syncEngine.deleteNote(noteId)
        }
    }

    override fun scheduleRestore(noteId: Long) {
        scope.launch {
            syncEngine.restoreNote(noteId)
        }
    }

    override fun clearPending() {
        // No-op: Desktop uses immediate execution rather than a persistent queue
    }
}
