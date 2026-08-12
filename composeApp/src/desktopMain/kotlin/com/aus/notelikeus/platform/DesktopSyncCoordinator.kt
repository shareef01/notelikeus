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
    @Volatile private var consecutiveFailures = 0
    private val enqueueLock = Any()

    /** Incremented by [clearPending] so an in-flight [flush] can detect it was signed out. */
    @Volatile private var queueGeneration = 0

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

    /** A note is only ever in one queue; the newest intent for it wins.
     * Synchronized so concurrent enqueues for the same noteId cannot leave it in two queues. */
    private fun enqueue(noteId: Long, target: MutableSet<Long>) {
        synchronized(enqueueLock) {
            for (queue in listOf(pendingUploads, pendingDeletes, pendingRestores)) {
                if (queue !== target) queue.remove(noteId)
            }
            target.add(noteId)
        }
        consecutiveFailures = 0
        persist()
        scheduleFlush()
    }

    override fun clearPending() {
        flushJob?.cancel()
        synchronized(enqueueLock) {
            // Bumped so a flush already in flight can tell its work belongs to a previous
            // session and drop it, rather than re-queueing a signed-out account's notes.
            queueGeneration++
            pendingUploads.clear()
            pendingDeletes.clear()
            pendingRestores.clear()
        }
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
     *
     * Draining takes a note out of every queue, so [enqueue]'s "newest intent wins" rule cannot
     * see it while the operation is running. Each loop therefore re-checks the other queues
     * before acting: without that, draining an upload and then deleting the note mid-flush would
     * upload it *after* the delete had already been processed, resurrecting it in the cloud.
     */
    private suspend fun flush() = flushMutex.withLock {
        val generation = queueGeneration
        val uploads: List<Long>
        val deletes: List<Long>
        val restores: List<Long>
        synchronized(enqueueLock) {
            uploads = drain(pendingUploads)
            deletes = drain(pendingDeletes)
            restores = drain(pendingRestores)
        }

        val failedUploads = mutableListOf<Long>()
        val failedDeletes = mutableListOf<Long>()
        val failedRestores = mutableListOf<Long>()

        for (noteId in deletes) {
            if (noteId in pendingUploads || noteId in pendingRestores) continue
            if (syncEngine.deleteNote(noteId).isFailure) failedDeletes += noteId
        }
        for (noteId in restores) {
            if (noteId in pendingUploads || noteId in pendingDeletes) continue
            if (syncEngine.restoreNote(noteId).isFailure) failedRestores += noteId
        }
        for (noteId in uploads) {
            if (noteId in pendingDeletes || noteId in pendingRestores) continue
            if (syncEngine.uploadNote(noteId).isFailure) failedUploads += noteId
        }

        // Signed out while this flush ran: the queues clearPending() emptied must stay empty.
        if (queueGeneration != generation) return@withLock

        val anyFailed = synchronized(enqueueLock) {
            // Skip anything a newer intent has since claimed — that queue entry supersedes ours.
            failedDeletes.forEach {
                if (it !in pendingUploads && it !in pendingRestores) pendingDeletes.add(it)
            }
            failedRestores.forEach {
                if (it !in pendingUploads && it !in pendingDeletes) pendingRestores.add(it)
            }
            failedUploads.forEach {
                if (it !in pendingDeletes && it !in pendingRestores) pendingUploads.add(it)
            }
            failedDeletes.isNotEmpty() || failedRestores.isNotEmpty() || failedUploads.isNotEmpty()
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
