package com.aus.notelikeus.data.remote

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aus.notelikeus.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Safety net for the per-note debounced sync in [CloudNoteSyncCoordinator]: that mechanism keeps
 * pending uploads in memory only, so a process death within its debounce window silently drops
 * the upload with nothing to retry it later. This worker periodically re-uploads every eligible
 * local note (last-write-wins, same as the manual "sync now" action), catching anything the
 * debounced path missed without needing to track which specific notes fell through.
 */
@HiltWorker
class ReconciliationSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val firebaseNoteSync: FirebaseNoteSync,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!settingsRepository.isCloudAutoSyncEnabled.first()) return Result.success()

        val result = firebaseNoteSync.uploadAllNotes()
        return if (result.isSuccess) {
            Result.success()
        } else if (runAttemptCount < MAX_RETRIES) {
            Result.retry()
        } else {
            Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "reconciliation_sync"
        private const val MAX_RETRIES = 3
    }
}
