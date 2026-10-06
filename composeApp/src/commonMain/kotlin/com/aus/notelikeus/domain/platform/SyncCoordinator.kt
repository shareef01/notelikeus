package com.aus.notelikeus.domain.platform

import com.aus.notelikeus.domain.repository.LocalCommitToken

/**
 * What happened to a token-aware schedule request.
 *
 * [RefusedStaleOrigin] is not a persistence failure and must never be reported as one: it means the
 * dataset the intent belonged to no longer exists, so the command was correctly *not* queued — the
 * local mutation it accompanies has either been wiped by the isolation that won, or was applied
 * before it. Storage and platform failures stay exceptions.
 */
enum class ScheduleOutcome {
    Enqueued,
    RefusedStaleOrigin,
}

/**
 * Abstraction for triggering background sync work when local data changes.
 * On Android, this triggers WorkManager via CloudNoteSyncCoordinator.
 * On Desktop, this might trigger a background upload or be a no-op for now.
 *
 * ## Two families, and the names say which is which
 *
 * R15.1 gave the scheduler a durable dataset epoch so a queued command could refuse to run against
 * the dataset that replaced its own. R15.2 closed the remaining hole beneath it: a scheduler call
 * that carried *no* originating authority had nothing to stamp and nothing to refuse with, so an
 * intent formed in dataset N and enqueued after N had been replaced entered the replacement
 * dataset's queue as the replacement dataset's own work.
 *
 * What fixed it is not another check but two APIs whose names cannot be confused:
 *
 *  - **`schedule*FromAction`** takes the [LocalCommitToken] the user's action captured at its real
 *    origin. It is resolved under `LocalCommitGate`, stamped with the epoch that was current in the
 *    same critical section, and refused outright if either has moved on by the time the command
 *    reaches the queue. This is the only way user-originated work may be queued.
 *  - **[scheduleCurrentDatasetSync]** takes no origin, because it has none: it is for work whose
 *    meaning *is* "make whatever dataset is current match the cloud". It stamps the dataset in force
 *    when it is queued, and is never correct for an action the user took earlier.
 *
 * The previous shape — `scheduleUpload(noteId)` beside `scheduleUpload(noteId, token)` — is gone on
 * purpose. Both compiled at every call site, so the safe one was a choice rather than the default,
 * and the unsafe one is what every pre-R15.2 caller had picked.
 */
interface SyncCoordinator {
    /**
     * Queues an upload on behalf of an operation that captured [origin].
     *
     * Scheduling happens **outside** `LocalCommitGate`, and that is deliberate: the local commit that
     * precedes it holds the gate, and putting a durable queue write or a WorkManager enqueue inside
     * it would let account isolation wait on disk I/O. What keeps the two steps honest is that the
     * token is revalidated under the gate here, together with the epoch the command is stamped with,
     * and the enqueue itself is serialized against rotation by the queue authority.
     */
    suspend fun scheduleUploadFromAction(noteId: Long, origin: LocalCommitToken): ScheduleOutcome

    /** See [scheduleUploadFromAction]. */
    suspend fun scheduleDeleteFromAction(noteId: Long, origin: LocalCommitToken): ScheduleOutcome

    /** See [scheduleUploadFromAction]. */
    suspend fun scheduleRestoreFromAction(noteId: Long, origin: LocalCommitToken): ScheduleOutcome

    /**
     * Queues a command with no originating intent, stamped with the dataset in force *now*.
     *
     * For maintenance semantics only — state that is meant to be reconciled against whatever dataset
     * is current when the command runs. It cannot refuse anything, because the caller never claimed a
     * dataset: that is exactly why it must not be reached from an action the user took at some
     * earlier point. Production callers are the repository's explicitly legacy overloads, which
     * exist for the routes that have no token to offer (see `NoteRepository`); everything else goes
     * through the `*FromAction` family.
     */
    suspend fun scheduleCurrentDatasetSync(kind: PendingSyncKind, noteId: Long)

    /**
     * Drops queued work without changing which dataset the device is on.
     *
     * For retry resets and local wipes that are *not* an account boundary. Rotating the epoch here
     * would strand every edit captured a moment earlier, so this deliberately keeps it.
     */
    suspend fun clearPending()

    /**
     * Phase 1 of a dataset transition: durably mark that an isolation is in flight.
     *
     * Called by account isolation **before** it crosses the boundary, and only there. From the write
     * onward — and in any process that restarts before [completeDatasetIsolation] — every scheduling
     * and execution decision refuses, because the device is between datasets: not on the one it is
     * leaving, and not yet on the one it is entering. If this write fails, nothing has happened at
     * all: no boundary was crossed, so the previous dataset remains legitimately current.
     *
     * Idempotent, so a retry after a failed attempt finishes the transition it started rather than
     * minting another one.
     */
    suspend fun beginDatasetIsolation()

    /**
     * Phase 2's completion: durably establish the new epoch and drop the previous dataset's queue.
     *
     * Called by account isolation **after** it has wiped the previous dataset's local rows, and only
     * there. Wipe-then-complete is deliberate: the other order would leave a window in which the
     * store says "on the new dataset" while the old dataset's rows are still on disk. Cancelling the
     * platform's in-flight jobs is cleanup on top, not the fence — a job that survives physically
     * still has to pass the epoch check before it may touch anything.
     *
     * This is the one scheduling-adjacent operation allowed to persist while `LocalCommitGate` is
     * held; see the invariant note on [com.aus.notelikeus.data.sync.LocalCommitGate].
     */
    suspend fun completeDatasetIsolation()

    /**
     * Whether the device is between datasets because an isolation began and has not completed.
     *
     * Read from the in-memory mirror, so it may be consulted under `LocalCommitGate` — which is what
     * lets the local-commit fence refuse account-owned writes on the residual dataset as well as
     * refusing to schedule them.
     */
    fun isDatasetIsolationIncomplete(): Boolean
}
