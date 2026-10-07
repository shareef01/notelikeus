package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.sync.DatasetAuthority
import com.aus.notelikeus.domain.platform.PendingSyncKind
import com.aus.notelikeus.domain.repository.SettingsRepository
import com.aus.notelikeus.domain.platform.ScheduleOutcome
import com.aus.notelikeus.domain.platform.SyncCoordinator
import com.aus.notelikeus.domain.repository.LocalCommitToken
import com.aus.notelikeus.data.sync.DatasetEpochAuthority
import com.aus.notelikeus.data.sync.LocalCommitGate
import com.aus.notelikeus.data.sync.PendingSyncCommand
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private const val DEBOUNCE_MS = 2_000L

/**
 * Debounces local edits into [SyncWorker] jobs, one per note.
 *
 * The queue itself lives in [DatasetEpochAuthority] rather than in this class, and that is the
 * point of R15.1. A set of note ids in memory cannot say *which dataset* queued them, and the ids
 * are exactly what the replacement dataset reuses: a delete queued for note 42 in generation N
 * would be handed to the engine as current work after an isolation, and the engine — called through
 * an ordinary entry point — would mint a fresh token for the dataset that replaced N and run it.
 *
 * So this class only decides *when* the queue is dispatched. [DatasetEpochAuthority] decides what
 * may be queued and whether what was queued still belongs to the device's dataset, and the epoch
 * travels with every WorkRequest so the worker can refuse it even if the cancellation that normally
 * follows an isolation never happened.
 */
class CloudNoteSyncCoordinator(
    private val sessionManager: CloudSessionManager,
    private val settingsRepository: SettingsRepository,
    private val workManager: WorkManager,
    private val epochAuthority: DatasetEpochAuthority,
) : SyncCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null

    init {
        // Work left over from a previous run is retried on the next launch, against the dataset the
        // store says this device is on — which is the same answer the process that queued it had,
        // unless an isolation has rotated the epoch in between, in which case that work is refused.
        scope.launch {
            // A device that is between datasets schedules nothing, whatever the store holds.
            if (epochAuthority.awaitAuthority() is DatasetAuthority.Stable &&
                !epochAuthority.currentPending().isEmpty
            ) {
                scheduleFlush()
            }
        }
    }

    // ---- the action-origin path: the only way a user's intent may be queued ----

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

    // ---- current-dataset maintenance: no originating intent to honour ----

    override suspend fun scheduleCurrentDatasetSync(kind: PendingSyncKind, noteId: Long) {
        // Refused outright while the device is between datasets: there is no current dataset to
        // maintain, and the next reconciliation re-derives this work from whatever the library is.
        if (!epochAuthority.enqueueCurrent(kind, noteId, sessionManager.getCurrentAccount().userId)) {
            return
        }
        scheduleFlush()
    }

    override suspend fun clearPending() {
        // The epoch stays: this drops retries, it does not claim a new dataset.
        cancelScheduledWork(keepEpoch = true)
    }

    override suspend fun beginDatasetIsolation() {
        epochAuthority.beginIsolation()
    }

    override suspend fun completeDatasetIsolation() {
        // The durable completion comes first, then the cancellation: a crash in between leaves jobs
        // carrying the old epoch, and the worker refuses those — whereas cancelling first would leave
        // the transition marked (so nothing runs either way) with the jobs' cleanup skipped.
        epochAuthority.completeIsolation()
        cancelScheduledWork(keepEpoch = false)
    }

    override fun isDatasetIsolationIncomplete(): Boolean = epochAuthority.isIsolationIncomplete()

    private suspend fun cancelScheduledWork(keepEpoch: Boolean) {
        flushJob?.cancel()
        val names = epochAuthority.currentPending().commands.map { uniqueWorkName(it.noteId) }
        if (keepEpoch) {
            epochAuthority.clearPending()
        }
        workManager.cancelAllWorkByTag(SyncWorker.WORK_TAG)
        for (name in names) {
            workManager.cancelUniqueWork(name)
        }
    }

    /**
     * Schedules work on behalf of an operation that captured [originatingToken].
     *
     * Deliberately *not* `enqueueFromCurrentDataset`: stamping the epoch that happens to be current
     * at enqueue time is the laundering bug with an extra step. An action that committed under
     * generation N, lost the race with an isolation, and then reached this method would have its
     * old intent entered into the replacement dataset's queue as that dataset's own work. Instead
     * the token and the epoch are resolved together under `LocalCommitGate`, and the enqueue itself
     * is conditional on that epoch under the queue authority's own serialization — so whichever of
     * enqueue and rotation wins, the loser cannot un-win.
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
            ownerUid = sessionManager.getCurrentAccount().userId,
        )
        if (!queued) return ScheduleOutcome.RefusedStaleOrigin
        scheduleFlush()
        return ScheduleOutcome.Enqueued
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
        if (!sessionManager.getCurrentAccount().isGoogleAccount) return

        // Each kind is drained together with the epoch it was drained under. Reading the epoch after
        // the drain would label commands taken out under E1 with whatever epoch is current by then —
        // which is precisely how a queued command gets laundered into the replacement dataset.
        val dispatches = listOf(
            epochAuthority.takeForDispatch(PendingSyncKind.UPLOAD) to false,
            epochAuthority.takeForDispatch(PendingSyncKind.DELETE) to true,
            epochAuthority.takeForDispatch(PendingSyncKind.RESTORE) to false,
        )

        for ((dispatch, isDelete) in dispatches) {
            val drained = dispatch ?: continue
            for (command in drained.commands) {
                enqueueSyncWork(
                    command = command,
                    isDelete = isDelete,
                    fallbackUid = drained.ownerUid,
                )
            }
        }
    }

    private fun enqueueSyncWork(
        command: PendingSyncCommand,
        isDelete: Boolean,
        fallbackUid: String?,
    ) {
        // The command's own uid, captured when it was queued, not the live session's: a session that
        // changed without an isolation is not a dataset change, but a *queued* command must still
        // name the account it was queued for. A command with neither is not dispatched at all.
        val expectedUid = command.ownerUid ?: fallbackUid ?: return
        val isRestore = command.kind == PendingSyncKind.RESTORE

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val data = Data.Builder()
            .putLong(SyncWorker.KEY_NOTE_ID, command.noteId)
            .putBoolean(SyncWorker.KEY_IS_DELETE, isDelete)
            .putBoolean(SyncWorker.KEY_IS_RESTORE, isRestore)
            .putString(SyncWorker.KEY_EXPECTED_UID, expectedUid)
            .putString(SyncWorker.KEY_DATASET_EPOCH, command.datasetEpoch.value)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .addTag(SyncWorker.WORK_TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName(command.noteId),
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    companion object {
        fun uniqueWorkName(noteId: Long): String = "sync_$noteId"
    }
}
