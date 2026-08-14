package com.aus.notelikeus.platform

import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.di.PendingSyncStore
import com.aus.notelikeus.domain.platform.SyncCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

    // Both are written from the flush coroutine and read/written from whatever thread calls
    // scheduleUpload/Delete/Restore, so neither read is safe without @Volatile: a stale flushJob
    // leaks a coroutine instead of replacing it, and a stale failure count picks the wrong backoff.
    @Volatile private var flushJob: Job? = null
    @Volatile private var consecutiveFailures = 0

    /** Completed once [init] has restored the pending-state snapshot from disk. */
    private val initLatch = CompletableDeferred<Unit>()

    /**
     * Bumped by [clearPending]; every deferred write carries the generation it was queued under and
     * drops itself if that no longer matches.
     *
     * Sign-out has to beat work that is already in flight, and both directions of that race were
     * live. A cancelled [flush] still runs its `finally` blocks — [runQueue] puts the drained ids
     * back and [flush] calls [persist] — so the queues repopulated *after* [clearPending] emptied
     * them, and since `persist()` and `pendingStore.clear()` are independent `scope.launch`es with
     * no ordering between them, the save could land last and leave the signed-out user's queue on
     * disk to be retried at next launch. The restore in `init` could do the same to a sign-out that
     * arrived while it was still reading. Comparing generations makes both stale writes no-ops.
     */
    @Volatile private var generation = 0

    init {
        val startedAt = generation
        scope.launch {
            try {
                val restored = pendingStore.load()
                // A sign-out landed while this read was in flight: those ids belong to the account
                // that just left, so restoring them would resurrect its queue.
                if (generation != startedAt) return@launch
                pendingUploads.addAll(restored.uploads)
                pendingDeletes.addAll(restored.deletes)
                pendingRestores.addAll(restored.restores)
                // Anything left over from a previous run is retried on the next launch.
                if (!restored.isEmpty) scheduleFlush()
            } finally {
                initLatch.complete(Unit)
            }
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
        persist()
        // While backing off, leave the pending retry where it is. Resetting the counter and
        // rescheduling at the 2s debounce here meant that a user editing notes offline — every
        // save lands in this method — retried every two seconds forever, which is precisely the
        // hammering the backoff exists to prevent. The id just added is picked up by the retry
        // that is already scheduled; only a successful flush clears the counter.
        if (consecutiveFailures == 0) scheduleFlush()
    }

    override fun clearPending() {
        generation++
        val clearedAt = generation
        flushJob?.cancel()
        pendingUploads.clear()
        pendingDeletes.clear()
        pendingRestores.clear()
        consecutiveFailures = 0
        scope.launch {
            initLatch.await()
            // Another sign-in/sign-out overtook this one; leave its state alone.
            if (generation != clearedAt) return@launch
            // Clear again before writing: the cancelled flush's `finally` blocks run after the
            // cancel above and re-add whatever they had drained, so the in-memory sets can be
            // non-empty by the time this runs.
            pendingUploads.clear()
            pendingDeletes.clear()
            pendingRestores.clear()
            pendingStore.clear()
        }
    }

    private fun persist() {
        val queuedAt = generation
        scope.launch {
            initLatch.await()
            // Queued before a sign-out; writing now would put that account's ids back on disk.
            if (generation != queuedAt) return@launch
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
        var anyFailed = false
        try {
            // Each queue is drained inside runQueue, immediately before its own work, rather than
            // all three up front — so there is never a window where restores and uploads sit
            // drained but unattempted while the deletes are still running.
            if (runQueue(pendingDeletes) { syncEngine.deleteNote(it) }) anyFailed = true
            if (runQueue(pendingRestores) { syncEngine.restoreNote(it) }) anyFailed = true
            if (runQueue(pendingUploads) { syncEngine.uploadNote(it) }) anyFailed = true
        } finally {
            // Reached on cancellation too. persist() hands the write to `scope`, which outlives
            // this job, so the restored queues still reach disk.
            persist()
        }

        if (anyFailed) {
            consecutiveFailures++
            scheduleFlush(retryDelayMs())
        } else {
            consecutiveFailures = 0
        }
    }

    /**
     * Drains [queue] and runs [operation] over it, putting anything that did not succeed back.
     * Returns true if at least one operation failed *for a reason worth backing off over*.
     *
     * That distinction is the point. [scheduleFlush] cancels the running flush job, so an edit
     * landing mid-sync cancels whatever is in flight — and [NoteSyncEngine] wraps every operation
     * in `runCatching`, which swallows `CancellationException` along with everything else. The
     * cancelled operations therefore come back as ordinary failed Results, indistinguishable from
     * a real cloud error. Counting them as one meant a user who simply kept typing pushed the
     * coordinator into its 30-second backoff, delaying the very edit they had just made. The work
     * is re-queued either way; only the backoff decision changes.
     *
     * The `finally` covers the other direction — a cancellation that propagates out rather than
     * being absorbed — so the drained-but-unattempted tail goes back on the queue instead of being
     * lost.
     */
    private suspend fun runQueue(
        queue: MutableSet<Long>,
        operation: suspend (Long) -> Result<Unit>
    ): Boolean {
        val ids = drain(queue)
        var failed = false
        var index = 0
        try {
            while (index < ids.size) {
                val error = operation(ids[index]).exceptionOrNull()
                if (error != null) {
                    queue.add(ids[index])
                    if (error !is CancellationException) failed = true
                }
                index++
            }
        } finally {
            if (index < ids.size) queue.addAll(ids.subList(index, ids.size))
        }
        return failed
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
