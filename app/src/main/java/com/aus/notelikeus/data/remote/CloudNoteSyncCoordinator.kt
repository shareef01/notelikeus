package com.aus.notelikeus.data.remote

import android.util.Log
import com.aus.notelikeus.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CloudNoteSync"
private const val DEBOUNCE_MS = 2_000L

@Singleton
class CloudNoteSyncCoordinator @Inject constructor(
    private val firebaseNoteSync: FirebaseNoteSync,
    private val firebaseSessionManager: FirebaseSessionManager,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingUploads = ConcurrentHashMap.newKeySet<Long>()
    private val pendingDeletes = ConcurrentHashMap.newKeySet<Long>()
    private var flushJob: Job? = null

    fun scheduleUpload(noteId: Long) {
        pendingDeletes.remove(noteId)
        pendingUploads.add(noteId)
        scheduleFlush()
    }

    fun scheduleDelete(noteId: Long) {
        pendingUploads.remove(noteId)
        pendingDeletes.add(noteId)
        scheduleFlush()
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
        pendingUploads.clear()
        pendingDeletes.clear()
        if (uploads.isEmpty() && deletes.isEmpty()) return

        deletes.forEach { noteId ->
            firebaseNoteSync.deleteNote(noteId)
                .onFailure { error ->
                    Log.w(TAG, "Auto-sync delete failed for $noteId", error)
                    pendingDeletes.add(noteId)
                }
        }

        uploads.forEach { noteId ->
            firebaseNoteSync.uploadNote(noteId)
                .onFailure { error ->
                    Log.w(TAG, "Auto-sync upload failed for $noteId", error)
                    pendingUploads.add(noteId)
                }
        }
    }
}
