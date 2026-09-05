package com.aus.notelikeus.data.remote

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.aus.notelikeus.data.sync.NoteSyncEngine
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {
    private val syncEngine: NoteSyncEngine by inject()
    private val sessionManager: CloudSessionManager by inject()

    override suspend fun doWork(): ListenableWorker.Result {
        val noteId = inputData.getLong(KEY_NOTE_ID, -1L)
        val isDelete = inputData.getBoolean(KEY_IS_DELETE, false)
        val isRestore = inputData.getBoolean(KEY_IS_RESTORE, false)
        val expectedUid = inputData.getString(KEY_EXPECTED_UID)

        if (noteId == -1L) return ListenableWorker.Result.failure()

        // Stale work from a prior session — do not touch the current user's cloud.
        val currentUid = sessionManager.getCurrentAccount().userId
        if (expectedUid.isNullOrBlank() || expectedUid != currentUid) {
            return ListenableWorker.Result.success()
        }

        val syncResult: kotlin.Result<*> = when {
            isDelete -> syncEngine.deleteNote(noteId)
            isRestore -> syncEngine.restoreNote(noteId)
            else -> syncEngine.uploadNote(noteId)
        }

        return if (syncResult.isSuccess) {
            ListenableWorker.Result.success()
        } else {
            if (runAttemptCount < MAX_RETRIES) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.failure()
            }
        }
    }

    companion object {
        const val WORK_TAG = "cloud_note_sync"
        const val KEY_NOTE_ID = "note_id"
        const val KEY_IS_DELETE = "is_delete"
        const val KEY_IS_RESTORE = "is_restore"
        const val KEY_EXPECTED_UID = "expected_uid"
        private const val MAX_RETRIES = 3
    }
}
