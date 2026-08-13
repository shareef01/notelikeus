package com.aus.notelikeus.domain.platform

/**
 * Abstraction for triggering background sync work when local data changes.
 * On Android, this triggers WorkManager via CloudNoteSyncCoordinator.
 * On Desktop, this might trigger a background upload or be a no-op for now.
 */
interface SyncCoordinator {
    fun scheduleUpload(noteId: Long)
    fun scheduleDelete(noteId: Long)
    fun scheduleRestore(noteId: Long)
    fun clearPending()
}
