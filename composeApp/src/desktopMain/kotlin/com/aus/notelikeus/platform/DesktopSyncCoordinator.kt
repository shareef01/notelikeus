package com.aus.notelikeus.platform

import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.di.PendingSyncStore
import com.aus.notelikeus.domain.platform.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Desktop [SyncCoordinator]: a debounced, persisted, retrying queue of per-note cloud writes.
 *
 * The previous implementation launched an immediate upload per mutation and kept no record of it,
 * so a write that failed — offline, expired token, server error — was lost with nothing to retry
 * from, and `clearPending()` did nothing on sign-out. This mirrors what Android gets from
 * `CloudNoteSyncCoordinator` plus WorkManager: coalesce a burst of edits, survive restart, and
 * back off rather than hammer a cloud that is not answering.
 */
class DesktopSyncCoordinator(
    private val syncEngine: NoteSyncEngine,
    private val pendingStore: PendingSyncStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : SyncCoordinator {

    private val pendingUploads = ConcurrentHashMap.newKeySet<Long>()
    private val pendingDeletes = ConcurrentHashMap.newKeySet<Long>()
    private val pendingRestores = ConcurrentHashMap.newKeySet<Long>()

    private val flushMutex = Mutex()
    private var flushJob: Job? = null
    private var consecutiveFailures = 0

    init {
        scope.launch {
            val restored = pendingStore.load()
            pendingUploads.addAll(restored.uploads)
            pendingDeletes.addAll(restored.deletes)
            pendingRestores.addAll(restored.restores)
            // Anything left over from a previous run is retried on the next launch.
            if (!restored.isEmpty) scheduleFlush()
        }
    }

    override fun scheduleUpload(noteId: Long) = enqueue(noteId, pendingUploads)

    override fun scheduleDelete(noteId: Long) = enqueue(noteId, pendingDeletes)

    override fun scheduleRestore(noteId: Long) = enqueue(noteId, pendingRestores)

    /** A note is only ever in one queue; the newest intent for it wins. */
    private fun enqueue(noteId: Long, target: MutableSet<Long>) {
        for (queue in listOf(pendingUploads, pendingDeletes, pendingRestores)) {
            if (queue !== target) queue.remove(noteId)
        }
        target.add(noteId)
        consecutiveFailures = 0
        persist()
        scheduleFlush()
    }

    override fun clearPending() {
        flushJob?.cancel()
        pendingUploads.clear()
        pendingDeletes.clear()
        pendingRestores.clear()
        consecutiveFailures = 0
        scope.launch { pendingStore.clear() }
    }

    private fun persist() {
        scope.launch {
            pendingStore.save(
                pendingUploads.toSet(),
                pendingDeletes.toSet(),
                pendingRestores.toSet()
            )
        }
    }

    private fun scheduleFlush(delayMs: Long = DEBOUNCE_MS) {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(delayMs)
            flush()
        }
    }

    /**
     * Drains each queue and runs its operations, returning failures to the queue so the next
     * attempt picks them up. Serialized so a retry cannot overlap the flush that scheduled it.
     */
    private suspend fun flush() = flushMutex.withLock {
        val uploads = drain(pendingUploads)
        val deletes = drain(pendingDeletes)
        val restores = drain(pendingRestores)
        var anyFailed = false

        for (noteId in deletes) {
            if (syncEngine.deleteNote(noteId).isFailure) {
                pendingDeletes.add(noteId)
                anyFailed = true
            }
        }
        for (noteId in restores) {
            if (syncEngine.restoreNote(noteId).isFailure) {
                pendingRestores.add(noteId)
                anyFailed = true
            }
        }
        for (noteId in uploads) {
            if (syncEngine.uploadNote(noteId).isFailure) {
                pendingUploads.add(noteId)
                anyFailed = true
            }
        }

        persist()

        if (anyFailed) {
            consecutiveFailures++
            scheduleFlush(retryDelayMs())
        } else {
            consecutiveFailures = 0
        }
    }

    private fun drain(queue: MutableSet<Long>): List<Long> {
        val snapshot = queue.toList()
        queue.removeAll(snapshot.toSet())
        return snapshot
    }

    /** Exponential backoff from 30s, capped at 15 minutes. */
    private fun retryDelayMs(): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 30)
        return min(RETRY_BASE_MS shl exponent, MAX_RETRY_MS)
    }

    private companion object {
        const val DEBOUNCE_MS = 2_000L
        const val RETRY_BASE_MS = 30_000L
        const val MAX_RETRY_MS = 15 * 60 * 1000L
    }
}
