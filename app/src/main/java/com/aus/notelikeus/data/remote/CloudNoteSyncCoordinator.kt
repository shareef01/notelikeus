package com.aus.notelikeus.data.remote

import com.aus.notelikeus.domain.repository.SettingsRepository
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val DEBOUNCE_MS = 2_000L

@Singleton
class CloudNoteSyncCoordinator @Inject constructor(
    private val firebaseNoteSync: FirebaseNoteSync,
    private val firebaseSessionManager: FirebaseSessionManager,
    private val settingsRepository: SettingsRepository,
    private val workManager: WorkManager,
    private val pendingStore: PendingCloudSyncStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingUploads = ConcurrentHashMap.newKeySet<Long>()
    private val pendingDeletes = ConcurrentHashMap.newKeySet<Long>()
    private val pendingRestores = ConcurrentHashMap.newKeySet<Long>()
    private var flushJob: Job? = null

    init {
        pendingUploads.addAll(pendingStore.pendingUploads())
        pendingDeletes.addAll(pendingStore.pendingDeletes())
        pendingRestores.addAll(pendingStore.pendingRestores())
        if (pendingUploads.isNotEmpty() || pendingDeletes.isNotEmpty() || pendingRestores.isNotEmpty()) {
            scheduleFlush()
        }
    }

    fun scheduleUpload(noteId: Long) {
        pendingDeletes.remove(noteId)
        pendingRestores.remove(noteId)
        pendingUploads.add(noteId)
        persistPending()
        scheduleFlush()
    }

    fun scheduleDelete(noteId: Long) {
        pendingUploads.remove(noteId)
        pendingRestores.remove(noteId)
        pendingDeletes.add(noteId)
        persistPending()
        scheduleFlush()
    }

    /**
     * Undo of a permanent delete. Distinct from [scheduleUpload] because a plain upload is
     * vetoed by the note's own tombstone — the restore has to drop the tombstone first.
     */
    fun scheduleRestore(noteId: Long) {
        pendingUploads.remove(noteId)
        pendingDeletes.remove(noteId)
        pendingRestores.add(noteId)
        persistPending()
        scheduleFlush()
    }

    fun clearPending() {
        flushJob?.cancel()
        // Drop already-enqueued WorkManager jobs so they cannot run under a new Firebase user.
        workManager.cancelAllWorkByTag(SyncWorker.WORK_TAG)
        for (noteId in pendingUploads + pendingDeletes + pendingRestores) {
            workManager.cancelUniqueWork(uniqueWorkName(noteId))
        }
        pendingUploads.clear()
        pendingDeletes.clear()
        pendingRestores.clear()
        pendingStore.clear()
    }

    private fun persistPending() {
        pendingStore.save(pendingUploads.toSet(), pendingDeletes.toSet(), pendingRestores.toSet())
    }

    private fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(DEBOUNCE_MS)
            flushPendingChanges()
        }
    }

    internal suspend fun flushNowForTest() {
        flushJob?.cancel()
        flushPendingChanges()
    }

    private suspend fun flushPendingChanges() {
        if (!settingsRepository.isCloudAutoSyncEnabled.first()) return
        if (!firebaseSessionManager.getCurrentAccount().isGoogleAccount) return

        val uploads = pendingUploads.toList()
        val deletes = pendingDeletes.toList()
        val restores = pendingRestores.toList()
        pendingUploads.clear()
        pendingDeletes.clear()
        pendingRestores.clear()
        pendingStore.clear()

        uploads.forEach { noteId ->
            enqueueSyncWork(noteId, isDelete = false)
        }

        deletes.forEach { noteId ->
            enqueueSyncWork(noteId, isDelete = true)
        }

        restores.forEach { noteId ->
            enqueueSyncWork(noteId, isDelete = false, isRestore = true)
        }
    }

    private fun enqueueSyncWork(noteId: Long, isDelete: Boolean, isRestore: Boolean = false) {
        val expectedUid = firebaseSessionManager.getCurrentAccount().userId ?: return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val data = Data.Builder()
            .putLong(SyncWorker.KEY_NOTE_ID, noteId)
            .putBoolean(SyncWorker.KEY_IS_DELETE, isDelete)
            .putBoolean(SyncWorker.KEY_IS_RESTORE, isRestore)
            .putString(SyncWorker.KEY_EXPECTED_UID, expectedUid)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .addTag(SyncWorker.WORK_TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName(noteId),
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    companion object {
        fun uniqueWorkName(noteId: Long): String = "sync_$noteId"
    }
}
