package com.aus.notelikeus.data.remote

import android.content.Context
import com.aus.notelikeus.domain.repository.SettingsRepository
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first

/**
 * Safety net for the per-note debounced sync in [CloudNoteSyncCoordinator]: that mechanism keeps
 * pending uploads in memory only, so a process death within its debounce window silently drops
 * the upload with nothing to retry it later. This worker periodically re-checks local notes and
 * re-uploads anything the debounced path missed. It uses [FirebaseNoteSync.reconcileUploads],
 * which only reads notes changed since the last reconcile rather than the whole cloud collection.
 */
class ReconciliationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val firebaseNoteSync: FirebaseNoteSync,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!settingsRepository.isCloudAutoSyncEnabled.first()) return Result.success()

        val result = firebaseNoteSync.reconcileUploads()
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
