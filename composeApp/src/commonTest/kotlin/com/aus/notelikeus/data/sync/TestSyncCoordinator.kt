package com.aus.notelikeus.data.sync

import com.aus.notelikeus.domain.platform.PendingSyncKind
import com.aus.notelikeus.domain.platform.ScheduleOutcome
import com.aus.notelikeus.domain.platform.SyncCoordinator
import com.aus.notelikeus.domain.repository.LocalCommitToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex

/**
 * A [SyncCoordinator] that records what was asked of it, shared by every lane that needs one.
 *
 * The token-aware overloads validate the originating token the way a production coordinator does —
 * under `LocalCommitGate` — so "a stale action schedules nothing" stays a real observation rather
 * than a fake that agrees with the call site.
 */
open class RecordingSyncCoordinator : SyncCoordinator {
    val uploads = mutableListOf<Long>()
    val deletes = mutableListOf<Long>()
    val restores = mutableListOf<Long>()

    /**
     * Current-dataset work, kept apart from [uploads]/[deletes]/[restores] on purpose.
     *
     * "An upload was scheduled" and "an upload was scheduled *without* originating authority" are
     * different observations, and a lane that asserts the first must not pass when the second
     * happened.
     */
    val currentDatasetSyncs = mutableListOf<Pair<PendingSyncKind, Long>>()

    /** Every queue-dropping transition: `clearAllUserData` clears, account isolation rotates. */
    var clearPendingCount = 0
    var rotateCount = 0

    /** Dataset transitions this coordinator was asked to begin and to complete. */
    var beginCount = 0
        private set
    var completeCount = 0
        private set

    fun reset() {
        uploads.clear()
        deletes.clear()
        restores.clear()
        currentDatasetSyncs.clear()
    }

    override suspend fun scheduleUploadFromAction(
        noteId: Long,
        origin: LocalCommitToken,
    ): ScheduleOutcome = record(uploads, noteId, origin)

    override suspend fun scheduleDeleteFromAction(
        noteId: Long,
        origin: LocalCommitToken,
    ): ScheduleOutcome = record(deletes, noteId, origin)

    override suspend fun scheduleRestoreFromAction(
        noteId: Long,
        origin: LocalCommitToken,
    ): ScheduleOutcome = record(restores, noteId, origin)

    override suspend fun scheduleCurrentDatasetSync(kind: PendingSyncKind, noteId: Long) {
        currentDatasetSyncs += kind to noteId
        when (kind) {
            PendingSyncKind.UPLOAD -> uploads += noteId
            PendingSyncKind.DELETE -> deletes += noteId
            PendingSyncKind.RESTORE -> restores += noteId
        }
    }

    override suspend fun clearPending() {
        clearPendingCount++
    }

    /**
     * Whether the fake is standing in for a device between datasets.
     *
     * Flipped by a lane that wants the transition refusals without a real dataset authority; the
     * token-aware overloads refuse while it is set, exactly as production's do.
     */
    var isolationIncomplete = false
        private set

    override suspend fun beginDatasetIsolation() {
        isolationIncomplete = true
        beginCount++
    }

    override suspend fun completeDatasetIsolation() {
        isolationIncomplete = false
        completeCount++
        rotateCount++
    }

    override fun isDatasetIsolationIncomplete(): Boolean = isolationIncomplete

    private fun record(
        target: MutableList<Long>,
        noteId: Long,
        originatingToken: LocalCommitToken,
    ): ScheduleOutcome {
        if (isolationIncomplete) {
            return ScheduleOutcome.RefusedStaleOrigin
        }
        if (LocalCommitGate.currentGeneration() != originatingToken.generation) {
            return ScheduleOutcome.RefusedStaleOrigin
        }
        target += noteId
        return ScheduleOutcome.Enqueued
    }
}

/** The same double, for lanes whose coordinator is not the subject under test. */
class NoopSyncCoordinator : RecordingSyncCoordinator()

/**
 * A durable epoch store in memory, standing in for the platform's SharedPreferences or DataStore.
 *
 * Deliberately keeps its state in a field that outlives the [DatasetEpochAuthority] built over it,
 * so a test can model a process restart as "a new authority, the same store" — which is the only
 * honest way to ask whether the epoch survives one.
 *
 * [gatedLoad] models the slow disk read the coordinators must not race, and [writes] records every
 * write so a test can assert that a rotation is a *single* one: the atomicity the crash-safety
 * argument rests on.
 */
class InMemoryDatasetEpochStore(
    initial: DatasetPending = DatasetPending()
) : DatasetEpochStore {
    var durable: DatasetPending = initial
        private set

    val writes = mutableListOf<DatasetPending>()
    var loadCount = 0

    /** Set to block `load()` until the test releases it. */
    var gatedLoad: CompletableDeferred<Unit>? = null

    /** Runs inside `save`, before the write lands: lets a test throw to model a failed write. */
    var beforeSave: (suspend (DatasetPending) -> Unit)? = null

    /**
     * When set, the store records (and, with [failUnderGate], refuses) any load or write that happens
     * while this lock is held.
     *
     * "No durable I/O while `LocalCommitGate` is held" is a behavioural claim about a path, not a
     * property of the code's shape, so it is checked by watching the store rather than by reading the
     * call site.
     */
    var gateLock: Mutex? = null
    var failUnderGate = false
    var touchedUnderGate = false

    override suspend fun load(): DatasetPending {
        checkGate()
        loadCount++
        gatedLoad?.await()
        return durable
    }

    override suspend fun save(pending: DatasetPending) {
        checkGate()
        beforeSave?.invoke(pending)
        writes += pending
        durable = pending
    }

    private fun checkGate() {
        val lock = gateLock ?: return
        if (!lock.isLocked) return
        touchedUnderGate = true
        check(!failUnderGate) { "durable epoch I/O performed while LocalCommitGate was held" }
    }

    /**
     * An authority over this store — a fresh process when called twice, since the epoch lives in
     * [durable] rather than in the authority that reads it.
     */
    fun authority(mint: () -> DatasetEpoch = { randomDatasetEpoch() }): DatasetEpochAuthority =
        DatasetEpochAuthority(this, mint)
}

/**
 * The token an action captures at its real origin, for lanes that never isolate.
 *
 * Captured through the same gate production uses, so a lane that *does* isolate can capture one
 * before the boundary and watch the corresponding route refuse it.
 */
fun actionToken(uid: String? = null): LocalCommitToken = LocalCommitGate.capture(uid)

/** The epoch the device is settled on, for lanes that are not about the transition state. */
suspend fun DatasetEpochAuthority.awaitStableEpoch(): DatasetEpoch =
    checkNotNull(awaitStableEpochOrNull()) { "the device is between datasets" }

/**
 * A completed dataset transition, in the order production uses: mark it, then finish it inside the
 * gate. The lanes that replace a dataset use this so the shape they exercise is the shipped one.
 */
suspend fun DatasetEpochAuthority.isolateToNewDataset(): DatasetEpoch {
    beginIsolation()
    return LocalCommitGate.isolate { completeIsolation() }
}
