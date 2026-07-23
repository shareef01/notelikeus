package com.aus.notelikeus.data.remote

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val firebaseNoteSync: FirebaseNoteSync,
    private val firebaseSessionManager: FirebaseSessionManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val noteId = inputData.getLong(KEY_NOTE_ID, -1L)
        val isDelete = inputData.getBoolean(KEY_IS_DELETE, false)
        val isRestore = inputData.getBoolean(KEY_IS_RESTORE, false)
        val expectedUid = inputData.getString(KEY_EXPECTED_UID)

        if (noteId == -1L) return Result.failure()

        // Stale work from a prior Firebase session — do not touch the current user's cloud.
        val currentUid = firebaseSessionManager.getCurrentAccount().userId
        if (expectedUid.isNullOrBlank() || expectedUid != currentUid) {
            return Result.success()
        }

        val result = when {
            isDelete -> firebaseNoteSync.deleteNote(noteId)
            isRestore -> firebaseNoteSync.restoreNote(noteId)
            else -> firebaseNoteSync.uploadNote(noteId)
        }

        return if (result.isSuccess) {
            Result.success()
        } else {
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure()
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
