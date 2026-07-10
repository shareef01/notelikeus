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
    private val firebaseNoteSync: FirebaseNoteSync
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val noteId = inputData.getLong(KEY_NOTE_ID, -1L)
        val isDelete = inputData.getBoolean(KEY_IS_DELETE, false)

        if (noteId == -1L) return Result.failure()

        val result = if (isDelete) {
            firebaseNoteSync.deleteNote(noteId)
        } else {
            firebaseNoteSync.uploadNote(noteId)
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
        const val KEY_NOTE_ID = "note_id"
        const val KEY_IS_DELETE = "is_delete"
        private const val MAX_RETRIES = 3
    }
}
