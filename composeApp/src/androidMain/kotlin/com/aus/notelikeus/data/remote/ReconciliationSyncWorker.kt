package com.aus.notelikeus.data.remote

import android.content.Context
import com.aus.notelikeus.domain.repository.SettingsRepository
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.aus.notelikeus.data.sync.NoteSyncEngine
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.coroutines.flow.first

/**
 * Safety net for the per-note debounced sync in [CloudNoteSyncCoordinator]: that mechanism keeps
 * pending uploads in memory only, so a process death within its debounce window silently drops
 * the upload with nothing to retry it later. This worker periodically re-checks local notes and
 * re-uploads anything the debounced path missed. It uses [NoteSyncEngine.reconcileUploads],
 * which only reads notes changed since the last reconcile rather than the whole cloud collection.
 */
class ReconciliationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {
    private val syncEngine: NoteSyncEngine by inject()
    private val settingsRepository: SettingsRepository by inject()

    override suspend fun doWork(): ListenableWorker.Result {
        if (!settingsRepository.isCloudAutoSyncEnabled.first()) return ListenableWorker.Result.success()

        val result: kotlin.Result<*> = syncEngine.reconcileUploads()
        return if (result.isSuccess) {
            ListenableWorker.Result.success()
        } else if (runAttemptCount < MAX_RETRIES) {
            ListenableWorker.Result.retry()
        } else {
            ListenableWorker.Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "reconciliation_sync"
        private const val MAX_RETRIES = 3
    }
}
