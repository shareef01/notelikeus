package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.platform.PendingSyncKind
import com.aus.notelikeus.util.AppLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Durable storage for the delayed-sync queue, the dataset epoch it belongs to, and whether an
 * isolation is in flight.
 *
 * Implementations must make [save] a **single atomic store write**: the epoch, the queue and the
 * isolation marker are written together, so a crash cannot leave a rotated epoch — or a half-taken
 * transition — sitting next to the previous dataset's commands. Both platform stores satisfy this by
 * construction: Android uses one `SharedPreferences.Editor.commit()`, desktop one DataStore
 * `edit { }`, and neither may be split into separate writes to "simplify" it. The commands each carry
 * their own epoch as well, so even a backend that could not offer atomicity would fail closed rather
 * than launder; atomicity is what keeps the *cleanup* honest, not what makes the decision safe.
 */
interface DatasetEpochStore {
    /** Reads the durable epoch, queue and isolation marker. Never called under `LocalCommitGate`. */
    suspend fun load(): DatasetPending

    /**
     * Writes [pending] atomically. Never called under `LocalCommitGate` except by completion.
     *
     * **Atomicity is not failure-atomicity.** A `save` that throws may still have committed: the
     * contract is that a *write* is all-or-nothing, not that a failure the caller observes proves
     * nothing landed. Android's `SharedPreferences.Editor.commit()` reports a refused write as a
     * `false` return (which `PendingCloudSyncStore` turns into a throw) and has no suspension points,
     * so in practice its failure means "not written"; desktop's `DataStore.edit` is a suspend call
     * whose cancellation or I/O failure can be observed after the file swap, so in practice its
     * failure can mean either. A caller that needs to know must therefore read the record back —
     * `DatasetEpochAuthority.beginIsolation` does exactly that, and must not be "simplified" into
     * trusting the exception.
     */
    suspend fun save(pending: DatasetPending)
}

/**
 * The one place that decides whether delayed sync work may run — R15.1, R15.2, and R15.3's
 * fail-closed isolation transition.
 *
 * It owns three things that must move together: the durable [DatasetEpoch] the device is on, the
 * queue of commands stamped with the epoch they were queued under, and — since R15.3 — the marker
 * that says a transition between datasets began and has not finished. Every decision it makes happens
 * inside [mutex], which is the serialization domain shared by *enqueue*, *isolation* and *queue
 * drop*. That is what gives those orders a definite winner:
 *
 * ```
 * enqueue first    the E1 command is durable; isolation then takes the transition and drops it — and
 *                  if the drop is interrupted, the marker still refuses it
 * isolation first  the device is between datasets, so enqueue(E1) is refused outright
 * ```
 *
 * ## The transition, in order
 *
 * ```
 * Stable(E1)                        ordinary operation
 *   | beginIsolation()               one durable write: epoch E1, isolationTarget E2, queue kept
 *   v
 * Isolating(E1 -> E2)                every scheduled authority is refused; durable; survives restart
 *   | wipe (idempotent, inside LocalCommitGate.isolate)
 *   | completeIsolation()            one durable write: epoch E2, isolationTarget cleared, queue dropped
 *   v
 * Stable(E2)
 * ```
 *
 * Four rules keep the crash windows honest:
 *
 *  - the epoch lives on disk, not in a counter, so a new process reads the same answer;
 *  - the marker is durable and is written *before* the account boundary is crossed, so every crash
 *    between that point and the final write reads back as "between datasets";
 *  - "between datasets" refuses everything — work from the old epoch, work from the new one, and
 *    current-dataset maintenance — because the one question all of them ask is "is this the dataset
 *    the device is on?", and the answer is "there is no such dataset yet";
 *  - a *missing* epoch fails closed — commands written by a version that predates epochs are dropped
 *    instead of being stamped with whatever is current (that stamping is the laundering this whole
 *    change exists to stop).
 *
 * An initialized mirror is the only thing the hot paths read, so validation never performs disk I/O
 * while `LocalCommitGate` is held. [awaitAuthority] does the reading, and its callers are all outside
 * the gate.
 */
class DatasetEpochAuthority(
    private val store: DatasetEpochStore,
    private val mint: () -> DatasetEpoch = { randomDatasetEpoch() },
) {
    /**
     * The serialization domain shared by enqueue, isolation and queue drop. `LocalCommitGate` is the
     * only other lock in this area, and the two have a fixed order — see [completeIsolation], where
     * both are held.
     */
    private val mutex = Mutex()

    /**
     * The initialized in-memory mirror of the durable state.
     *
     * Read without [mutex] by the boundary-free paths ([authorityOrNull], [currentEpochOrNull]) and
     * by the engine's origin resolution, which runs *inside* `LocalCommitGate` and therefore must not
     * touch disk. Only ever written under [mutex].
     */
    @Volatile
    private var state: DatasetPending = DatasetPending.Uninitialized

    /**
     * Bumped whenever the queue is dropped, so writes that were already in flight when it was cleared
     * — a flush putting failed ids back, for instance — cannot repopulate it. Only touched under
     * [mutex].
     */
    private var queueGeneration: Long = 0L

    /** The durable state the device is on, or `null` before the first read. Never performs I/O. */
    fun authorityOrNull(): DatasetAuthority? = state.authorityOrNull()

    /** Whether an isolation began and has not completed. Never performs I/O. */
    fun isIsolationIncomplete(): Boolean = state.isIsolating

    /**
     * The current epoch, or `null` when the device is between datasets — or before the first read.
     *
     * The two `null` cases are deliberately the same answer: every caller of this method is asking
     * "may work carrying *this* epoch run now?", and both mean no. It is what makes the whole
     * scheduling path fail closed against an incomplete isolation without a single extra check.
     */
    fun currentEpochOrNull(): DatasetEpoch? = (state.authorityOrNull() as? DatasetAuthority.Stable)?.epoch

    /** The in-memory view of the queue and transition. Diagnostics and tests; never performs I/O. */
    fun currentPending(): DatasetPending = state

    /**
     * Reads the durable authority once, minting and persisting a first epoch if the store has none.
     *
     * This is the only place the hot paths read disk, and every caller is outside `LocalCommitGate` —
     * a durable read inside the gate would let a sign-in wait on file I/O. The answer is
     * [DatasetAuthority.Isolating] while a transition is unfinished, and callers must treat that as a
     * refusal rather than as "some epoch".
     */
    suspend fun awaitAuthority(): DatasetAuthority = mutex.withLock {
        val loaded = ensureLoadedLocked()
        loaded.isolationTarget?.let { return@withLock DatasetAuthority.Isolating(loaded.epoch, it) }
        // Total by construction: ensureLoadedLocked persists a freshly minted epoch when the store
        // has none, so a loaded state always carries either an epoch or a transition.
        DatasetAuthority.Stable(checkNotNull(loaded.epoch) { "loaded dataset state has no epoch" })
    }

    /**
     * The current epoch, or `null` while the device is between datasets.
     *
     * For diagnostics and tests. A decision must use [awaitAuthority] and branch on the two states:
     * this collapses them into one nullable value, and every caller that treats `null` as anything
     * other than "refuse" would be re-opening the hole this exists to close.
     */
    suspend fun awaitStableEpochOrNull(): DatasetEpoch? = awaitAuthority().let {
        (it as? DatasetAuthority.Stable)?.epoch
    }

    /**
     * Phase 1 of a dataset transition: one durable write marking an isolation in flight.
     *
     * Deliberately **before** the account boundary is crossed (see `LocalAccountIsolator.isolate`).
     * If this write succeeds, every scheduled authority is refused from here until
     * [completeIsolation] — including across a restart.
     *
     * **A failed write is reconciled, not assumed.** `save` throwing does not by itself establish that
     * the marker was not committed — the store contract promises an atomic *write*, not that a failure
     * observed by the caller means "nothing was written" (a `DataStore.edit` can report a
     * cancellation after its file swap). So the failure path re-reads the durable record and the mirror
     * is made to match it: no transition durably present means the old dataset is still current and is
     * published again; a durably present transition keeps the pre-published quarantine; an unreadable
     * record keeps the quarantine too. The local generation has not moved in any of those cases, and
     * the failure is reported to the caller either way.
     *
     * **Publication order is part of the contract: the quarantine is published *before* the durable
     * write.** The in-memory mirror is what [isIsolationIncomplete] answers from, and that answer is
     * what every account-owned decision consults — `LocalCommitGate.commit`,
     * `authorizeRemoteMutationStart`, `resolveSchedulingEpoch` and `resolveScheduledOrigin` all read it
     * rather than the store. Publishing after the write would leave a window in which the transition is
     * already durable while those decisions still see a stable dataset: with the local generation also
     * unchanged, a matching-generation operation could mint new mutation authority for a dataset the
     * device has already left. Over-refusing during the write is safe — the work simply does not start
     * and the next attempt redoes it — whereas under-refusing is the hole this ordering closes.
     *
     * Idempotent: a retry after a failed attempt keeps the epoch the first attempt chose instead of
     * minting a chain of unrelated ones.
     */
    suspend fun beginIsolation(): DatasetEpoch {
        return mutex.withLock {
            val loaded = ensureLoadedLocked()
            loaded.isolationTarget?.let { return@withLock it }

            val next = mint()
            val pending = loaded.copy(isolationTarget = next)
            // Quarantine first, durable marker second. Every in-process decision reads this mirror, so
            // it has to fail closed from the instant the transition can become durable — see the
            // publication-order note above. Everything here is serialized by this mutex, which is what
            // makes the rollback below unambiguous.
            state = pending
            val persisted = runCatching { store.save(pending) }
            if (persisted.isFailure) {
                // A failed marker write is resolved by *asking the durable record*, never by assuming
                // what the failure meant. `save` throwing does not prove the marker was not committed:
                // a desktop `DataStore.edit` can surface a cancellation or an I/O failure after its
                // file swap, and an implementation is entitled to report a failure it noticed after
                // committing. The two outcomes are indistinguishable from the exception alone.
                //
                // So the authoritative read decides:
                //   - it is readable and carries no transition -> the marker truly did not commit, and
                //     the dataset that is still current is published again;
                //   - it is readable and carries a transition -> the marker did commit (this attempt's
                //     or an earlier one's), so the pre-published quarantine stays exactly as it is and
                //     the interrupted transition is left to the recovery that completes it;
                //   - it is unreadable -> nothing about the durable record can be established, so the
                //     quarantine stays published: fail closed rather than restore a dataset from
                //     memory alone.
                // Publishing the record verbatim is also what keeps the mirror and the durable store
                // from disagreeing after a failure. Every write and every read here is serialized by
                // this mutex, so the record read now cannot be superseded while it is being applied.
                state = runCatching { store.load() }.getOrNull() ?: pending
            }
            persisted.getOrThrow()
            AppLog.warn(
                TAG,
                "Dataset isolation from '${loaded.epoch?.value ?: "<unknown>"}' to " +
                    "'${next.value}' is in flight (durable): no scheduled work may run until it " +
                    "completes.",
            )
            next
        }
    }

    /**
     * Phase 2's completion: the device is on the new dataset, one durable write.
     *
     * Called **after** the previous dataset's local rows are wiped (see `LocalAccountIsolator`), so
     * the two orders the crash matrix cares about both fail closed: a crash before the wipe reads
     * back as [DatasetAuthority.Isolating], and so does one after it. There is no window in which the
     * store says "on the new dataset" while the old dataset's rows are still there.
     *
     * **It is the one place both locks are held, and the order is fixed.** Callers reach it from
     * inside a `LocalCommitGate.isolate` block, so the order is gate → this mutex. Nothing exists in
     * the other direction: no method of this class acquires the gate, and no caller acquires the gate
     * while holding this mutex — the scheduling path releases the gate before it reaches
     * [enqueueIfCurrent], which is why signing out cannot deadlock against a schedule. See the
     * invariant note on `LocalCommitGate`.
     */
    suspend fun completeIsolation(): DatasetEpoch {
        return mutex.withLock {
            queueGeneration++
            val loaded = ensureLoadedLocked()
            // The marked transition's target when there is one — the ordinary case — and otherwise a
            // fresh epoch, which is what a caller that completes a transition it never marked (tests,
            // and any future caller that replaces a dataset outright) is asking for.
            val nextEpoch = loaded.isolationTarget ?: mint()
            val next = DatasetPending(
                epoch = nextEpoch,
                ownerUid = null,
                commands = emptyList(),
                isolationTarget = null,
            )
            store.save(next)
            state = next
            AppLog.warn(TAG, "Dataset isolation completed: this device is on '${nextEpoch.value}'.")
            nextEpoch
        }
    }

    /**
     * Queues a command that carries no originating token.
     *
     * For current-dataset maintenance only: it stamps the dataset that is current *now*. Returns
     * false — and writes nothing — while the device is between datasets, because there is no current
     * dataset to stamp: doing so would attach maintenance work to whichever dataset happened to be
     * recorded, which is exactly what the transition marker exists to refuse.
     */
    suspend fun enqueueCurrent(kind: PendingSyncKind, noteId: Long, ownerUid: String?): Boolean {
        return mutex.withLock {
            val loaded = ensureLoadedLocked()
            val epoch = (loaded.authorityOrNull() as? DatasetAuthority.Stable)?.epoch
            if (epoch == null) {
                AppLog.warn(
                    TAG,
                    "Refused current-dataset ${kind.name.lowercase()} for note $noteId: the device " +
                        "is between datasets, so there is no current one to stamp it with.",
                )
                return@withLock false
            }
            persistLocked(loaded.withCommand(PendingSyncCommand(kind, noteId, epoch, ownerUid)))
            true
        }
    }

    /**
     * Queues [noteId] only if [expectedEpoch] is still the dataset the device is on.
     *
     * Returns false — and writes nothing — when the dataset moved on between the caller's
     * `LocalCommitGate` decision and this call, which includes the case where an isolation is in
     * flight: [currentEpochOrNull] is then `null`, so nothing is current and everything is refused.
     * That is the "isolation wins first" half of the transition contract; the other half is
     * [completeIsolation] dropping whatever is already queued.
     */
    suspend fun enqueueIfCurrent(
        expectedEpoch: DatasetEpoch,
        kind: PendingSyncKind,
        noteId: Long,
        ownerUid: String?,
    ): Boolean = mutex.withLock {
        val loaded = ensureLoadedLocked()
        if (loaded.epoch != expectedEpoch || loaded.isIsolating) {
            AppLog.warn(
                TAG,
                "Refused to queue ${kind.name.lowercase()} for note $noteId: the dataset it was " +
                    "authorised in (${expectedEpoch.value}) is no longer current.",
            )
            return@withLock false
        }
        persistLocked(loaded.withCommand(PendingSyncCommand(kind, noteId, expectedEpoch, ownerUid)))
        true
    }

    /**
     * Atomically reads the epoch and drains this kind's commands, so the two cannot disagree.
     *
     * Reading the epoch *after* draining would be the laundering bug in a new place: commands taken
     * out under E1 would be labelled with whatever epoch is current when they are handed to a worker.
     * Commands whose epoch is not the current one are dropped rather than returned — they are work
     * from a replaced dataset, and returning them is precisely what F-4 is. While an isolation is in
     * flight nothing at all is dispatchable, and the queue is left alone so that completion can drop
     * it in the same write that establishes the new epoch.
     */
    suspend fun takeForDispatch(kind: PendingSyncKind): PendingDispatch? {
        return mutex.withLock {
            val loaded = ensureLoadedLocked()
            val current = (loaded.authorityOrNull() as? DatasetAuthority.Stable)?.epoch
                ?: return@withLock null

            val queued = loaded.commandsOf(kind)
            if (queued.isEmpty()) return@withLock null

            val (runnable, refused) = queued.partition { it.datasetEpoch == current }
            if (refused.isNotEmpty()) {
                AppLog.warn(
                    TAG,
                    "Dropped ${refused.size} queued ${kind.name.lowercase()} command(s) from a " +
                        "dataset that is no longer current.",
                )
            }
            persistLocked(loaded.withoutCommands(kind))
            if (runnable.isEmpty()) {
                return@withLock null
            }
            PendingDispatch(
                epoch = current,
                ownerUid = runnable.last().ownerUid,
                generation = queueGeneration,
                kind = kind,
                commands = runnable,
            )
        }
    }

    /**
     * Puts one command back after a failed attempt, refusing to repopulate a queue that was cleared,
     * rotated or taken into a transition while the attempt was in flight.
     *
     * The refusal is what makes a cancelled flush safe on desktop: sign-out clears the queue and bumps
     * the generation, and the failed ids then arrive *after* it, so a plain re-add would resurrect the
     * departed account's work on disk.
     */
    suspend fun restoreAfterFailure(dispatch: PendingDispatch, command: PendingSyncCommand) {
        mutex.withLock {
            if (queueGeneration != dispatch.generation) return@withLock
            if (state.isIsolating) return@withLock
            if (command.datasetEpoch != state.epoch) return@withLock
            persistLocked(state.withCommand(command))
        }
    }

    /**
     * Drops the queued work and keeps the dataset state, including an in-flight transition.
     *
     * For callers that reset retries or wipe local data *without* the dataset changing. Rotating here
     * would strand every edit captured a moment earlier, and completing a transition here would
     * declare a dataset current before its wipe has run, so both are separate operations.
     */
    suspend fun clearPending() {
        mutex.withLock {
            queueGeneration++
            // While a transition is in flight the queue is dropped by the completion, in the same
            // write that establishes the new epoch, so there is nothing to persist here — and the
            // transition stays a bounded two writes rather than three.
            if (state.commands.isNotEmpty() && !state.isIsolating) {
                persistLocked(state.copy(commands = emptyList()))
            }
        }
    }

    private suspend fun ensureLoadedLocked(): DatasetPending {
        if (state.epoch != null || state.isIsolating) return state

        val loaded = store.load()
        val next = when {
            // A transition with no recorded epoch: completed rather than abandoned, because the wipe
            // it was going to perform has not happened yet and the target epoch it names is the only
            // datum that says which dataset the device should end up on.
            loaded.isIsolating -> loaded
            loaded.epoch != null -> {
                // Commands that name a different dataset were written before a transition whose clear
                // never completed. They are refused, and dropping them here is only cleanup.
                loaded.copy(commands = loaded.commands.filter { it.datasetEpoch == loaded.epoch })
            }

            else -> {
                if (loaded.commands.isNotEmpty()) {
                    AppLog.warn(
                        TAG,
                        "Dropped ${loaded.commands.size} queued command(s) with no dataset epoch: " +
                            "work queued before epochs existed cannot be attributed to a dataset, " +
                            "and assuming the current one would authorize it against a replacement.",
                    )
                }
                DatasetPending(epoch = mint(), ownerUid = null)
            }
        }
        if (next != loaded) store.save(next)
        state = next
        return next
    }

    private suspend fun persistLocked(pending: DatasetPending) {
        store.save(pending)
        state = pending
    }

    /**
     * Adds [command], dropping whatever the same note was queued for before it: a note is only ever
     * waiting on one cloud write, and the newest intent wins.
     */
    private fun DatasetPending.withCommand(command: PendingSyncCommand): DatasetPending =
        copy(commands = commands.filterNot { it.noteId == command.noteId } + command)

    private fun DatasetPending.withoutCommands(kind: PendingSyncKind): DatasetPending =
        copy(commands = commands.filterNot { it.kind == kind })

    private companion object {
        const val TAG = "DatasetEpochAuthority"
    }
}

/**
 * The authority this durable state represents, or `null` when nothing has been read yet.
 *
 * A record with no epoch and no transition is [DatasetAuthority.Stable] with a `null` epoch, which
 * callers read as "not current" — the same answer as an unfinished transition.
 */
private fun DatasetPending.authorityOrNull(): DatasetAuthority? = when {
    isolationTarget != null -> DatasetAuthority.Isolating(from = epoch, next = isolationTarget)
    epoch != null -> DatasetAuthority.Stable(epoch)
    else -> null
}

/**
 * The work drained for one kind, together with the epoch it was drained under and the queue
 * generation that was current at that moment.
 *
 * A worker or a flush may only hand these commands to the engine as coming from [epoch]; anything
 * that re-derives the epoch later is re-deriving it from a dataset that may since have been replaced.
 */
data class PendingDispatch(
    val epoch: DatasetEpoch,
    val ownerUid: String?,
    val generation: Long,
    val kind: PendingSyncKind,
    val commands: List<PendingSyncCommand>,
) {
    val isEmpty: Boolean get() = commands.isEmpty()
}
