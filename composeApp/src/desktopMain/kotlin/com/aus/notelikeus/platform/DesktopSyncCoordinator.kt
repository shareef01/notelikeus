package com.aus.notelikeus.platform

import com.aus.notelikeus.data.sync.DatasetAuthority
import com.aus.notelikeus.domain.platform.PendingSyncKind
import com.aus.notelikeus.data.sync.DatasetEpochAuthority
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.PendingDispatch
import com.aus.notelikeus.data.sync.PendingSyncCommand
import com.aus.notelikeus.data.sync.ScheduledWorkOrigin
import com.aus.notelikeus.data.sync.ScheduledWorkOutcome
import com.aus.notelikeus.domain.platform.ScheduleOutcome
import com.aus.notelikeus.domain.platform.SyncCoordinator
import com.aus.notelikeus.domain.repository.LocalCommitToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/**
 * Desktop [SyncCoordinator]: a debounced, persisted, retrying queue of per-note cloud writes.
 *
 * The queue state itself lives in [DatasetEpochAuthority] — the epoch the device is on, the commands
 * queued under it, and the serialization that makes an enqueue and a dataset rotation mutually
 * exclusive. What is left here is policy: when to flush, in what order, how long to back off, and
 * which commands still have the authority to run.
 *
 * That split is R15.1. Desktop has no WorkManager, so a queued command here is executed in-process
 * by [syncEngine]'s *scheduled* entry points, which resolve the command's own dataset epoch into the
 * token it executes under. Calling the ordinary `deleteNote`/`uploadNote`/`restoreNote` — as this
 * class used to — would capture a fresh token for whatever dataset is current when the queue drains,
 * which is how a delete queued before a sign-out ran against a replacement dataset's colliding ids.
 *
 * This mirrors what Android gets from `CloudNoteSyncCoordinator` plus WorkManager: coalesce a burst
 * of edits, survive restart, and back off rather than hammer a cloud that is not answering.
 */
class DesktopSyncCoordinator(
    private val syncEngine: NoteSyncEngine,
    private val epochAuthority: DatasetEpochAuthority,
    /**
     * The account a queued command is stamped for.
     *
     * Read when a command is queued, never when it runs: reading it at execution time would name
     * whichever account is live then, which is precisely the substitution the stamped origin exists
     * to prevent. It is defense in depth beside the epoch, which is the actual authority.
     */
    private val ownerUidProvider: suspend () -> String? = { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : SyncCoordinator {

    /**
     * Both are written from the flush coroutine and read/written from whatever thread calls
     * scheduleUpload/Delete/Restore, so neither read is safe without @Volatile: a stale flushJob
     * leaks a coroutine instead of replacing it, and a stale failure count picks the wrong backoff.
     */
    @Volatile private var flushJob: Job? = null
    @Volatile private var consecutiveFailures = 0

    /** Serializes flushes with each other, so a retry cannot overlap the flush that scheduled it. */
    private val flushMutex = Mutex()

    init {
        scope.launch {
            // Anything left over from a previous run is retried on the next launch, in the dataset
            // the durable store says this device is on. Commands that name a different dataset are
            // dropped by the authority as it reads them, and a device that is *between* datasets
            // schedules nothing at all.
            if (epochAuthority.awaitAuthority() is DatasetAuthority.Stable &&
                !epochAuthority.currentPending().isEmpty
            ) {
                scheduleFlush()
            }
        }
    }

    override suspend fun scheduleUploadFromAction(
        noteId: Long,
        origin: LocalCommitToken,
    ): ScheduleOutcome = scheduleForOrigin(origin, PendingSyncKind.UPLOAD, noteId)

    override suspend fun scheduleDeleteFromAction(
        noteId: Long,
        origin: LocalCommitToken,
    ): ScheduleOutcome = scheduleForOrigin(origin, PendingSyncKind.DELETE, noteId)

    override suspend fun scheduleRestoreFromAction(
        noteId: Long,
        origin: LocalCommitToken,
    ): ScheduleOutcome = scheduleForOrigin(origin, PendingSyncKind.RESTORE, noteId)

    override suspend fun clearPending() {
        flushJob?.cancel()
        consecutiveFailures = 0
        // The epoch is deliberately kept: this drops retries, it does not claim a new dataset.
        epochAuthority.clearPending()
    }

    override suspend fun beginDatasetIsolation() {
        epochAuthority.beginIsolation()
    }

    override suspend fun completeDatasetIsolation() {
        flushJob?.cancel()
        consecutiveFailures = 0
        epochAuthority.completeIsolation()
    }

    override fun isDatasetIsolationIncomplete(): Boolean = epochAuthority.isIsolationIncomplete()

    override suspend fun scheduleCurrentDatasetSync(kind: PendingSyncKind, noteId: Long) {
        // Refused outright while the device is between datasets — see the Android coordinator.
        if (!epochAuthority.enqueueCurrent(kind, noteId, ownerUidProvider())) {
            return
        }
        // While backing off, leave the pending retry where it is. Resetting the counter and
        // rescheduling at the 2s debounce here meant that a user editing notes offline — every
        // save lands in this method — retried every two seconds forever, which is precisely the
        // hammering the backoff exists to prevent. The id just added is picked up by the retry
        // that is already scheduled; only a successful flush clears the counter.
        if (consecutiveFailures == 0) scheduleFlush()
    }

    /**
     * Queues work on behalf of an operation that captured [originatingToken].
     *
     * The token and the epoch are resolved in one critical section — see
     * [LocalCommitGate.resolveSchedulingEpoch] — and the enqueue is conditional on that epoch under
     * the queue authority's own serialization, so neither an isolation that lands first nor one that
     * lands last can turn an old dataset's intent into the new dataset's work.
     */
    private suspend fun scheduleForOrigin(
        originatingToken: LocalCommitToken,
        kind: PendingSyncKind,
        noteId: Long,
    ): ScheduleOutcome {
        // The durable read, outside the gate: the gate must never wait on file I/O. A device between
        // datasets has no epoch anything may be authorized against, so this is a refusal — not a
        // reason to stamp the command with either the old or the new epoch.
        if (epochAuthority.awaitAuthority() !is DatasetAuthority.Stable) {
            return ScheduleOutcome.RefusedStaleOrigin
        }
        val epoch = LocalCommitGate.resolveSchedulingEpoch(originatingToken) {
            epochAuthority.currentEpochOrNull()
        } ?: return ScheduleOutcome.RefusedStaleOrigin

        val queued = epochAuthority.enqueueIfCurrent(
            expectedEpoch = epoch,
            kind = kind,
            noteId = noteId,
            ownerUid = ownerUidProvider(),
        )
        if (!queued) return ScheduleOutcome.RefusedStaleOrigin
        if (consecutiveFailures == 0) scheduleFlush()
        return ScheduleOutcome.Enqueued
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
     * Draining takes a note out of every queue, so the "newest intent wins" rule cannot see it while
     * the operation is running. Each loop therefore re-checks the other queues before acting:
     * without that, draining an upload and then deleting the note mid-flush would upload it *after*
     * the delete had already been processed, resurrecting it in the cloud.
     */
    private suspend fun flush() = flushMutex.withLock {
        var anyFailed = false
        // Each queue is drained inside runQueue, immediately before its own work, rather than all
        // three up front — so there is never a window where restores and uploads sit drained but
        // unattempted while the deletes are still running.
        if (
            runQueue(PendingSyncKind.DELETE) { command, origin ->
                syncEngine.deleteNoteFromScheduledWork(command.noteId, origin)
            }
        ) {
            anyFailed = true
        }
        if (
            runQueue(PendingSyncKind.RESTORE) { command, origin ->
                syncEngine.restoreNoteFromScheduledWork(command.noteId, origin)
            }
        ) {
            anyFailed = true
        }
        if (
            runQueue(PendingSyncKind.UPLOAD) { command, origin ->
                syncEngine.uploadNoteFromScheduledWork(command.noteId, origin)
            }
        ) {
            anyFailed = true
        }

        if (anyFailed) {
            consecutiveFailures++
            scheduleFlush(retryDelayMs())
        } else {
            consecutiveFailures = 0
        }
    }

    /**
     * Drains [kind] and runs [operation] over it, putting anything that did not succeed back.
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
     * lost. Putting it back goes through the authority, which refuses once the queue has been
     * cleared or rotated: a cancelled flush must not resurrect a signed-out account's queue.
     *
     * A command the engine *refuses* is dropped rather than re-queued. Its dataset is gone, so
     * re-queueing it would retry the one operation that must not run; and it is deliberately not
     * counted as a failure either, since a replaced dataset is not a cloud that is not answering.
     */
    private suspend fun runQueue(
        kind: PendingSyncKind,
        operation: suspend (PendingSyncCommand, ScheduledWorkOrigin) -> ScheduledWorkOutcome
    ): Boolean {
        val dispatch: PendingDispatch = epochAuthority.takeForDispatch(kind) ?: return false
        var failed = false
        var index = 0
        try {
            while (index < dispatch.commands.size) {
                failed = runQueuedCommand(dispatch, dispatch.commands[index], operation) || failed
                index++
            }
        } finally {
            requeueFrom(dispatch, index)
        }
        return failed
    }

    /**
     * Runs one drained command, putting it back when it failed for a reason worth retrying.
     *
     * Returns whether the failure is one the coordinator should back off on. A cancellation is
     * re-queued but not counted: [scheduleFlush] cancels the running flush, so an edit landing
     * mid-sync cancels whatever is in flight — counting those as cloud failures meant a user who
     * simply kept typing was pushed into the 30-second backoff, delaying the sync of the very edit
     * they had just made. A *refused* command is neither re-queued nor counted: its dataset is gone,
     * so retrying would retry the one operation that must not run.
     */
    private suspend fun runQueuedCommand(
        dispatch: PendingDispatch,
        command: PendingSyncCommand,
        operation: suspend (PendingSyncCommand, ScheduledWorkOrigin) -> ScheduledWorkOutcome,
    ): Boolean {
        val outcome = operation(command, command.toScheduledOrigin())
        if (outcome != ScheduledWorkOutcome.Retryable && outcome != ScheduledWorkOutcome.Cancelled) {
            return false
        }
        epochAuthority.restoreAfterFailure(dispatch, command)
        return outcome == ScheduledWorkOutcome.Retryable
    }

    /** Puts the drained-but-unattempted tail back, refusing once the queue was cleared or rotated. */
    private suspend fun requeueFrom(dispatch: PendingDispatch, fromIndex: Int) {
        for (command in dispatch.commands.drop(fromIndex)) {
            epochAuthority.restoreAfterFailure(dispatch, command)
        }
    }

    /**
     * The origin authority this command executes under.
     *
     * The epoch is the authority and the uid is defense in depth, so a command queued with no
     * session at all still resolves — the epoch decides, and the engine's own credential capture
     * binds the request to whichever account is live. What must never happen is *re-deriving* either
     * from the current state: both come from the command.
     */
    private fun PendingSyncCommand.toScheduledOrigin(): ScheduledWorkOrigin =
        ScheduledWorkOrigin(expectedUid = ownerUid, datasetEpoch = datasetEpoch)

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
