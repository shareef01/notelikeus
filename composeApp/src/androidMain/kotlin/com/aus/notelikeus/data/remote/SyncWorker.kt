package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.sync.DatasetAuthority
import com.aus.notelikeus.domain.platform.PendingSyncKind
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.aus.notelikeus.data.sync.DatasetEpoch
import com.aus.notelikeus.data.sync.DatasetEpochAuthority
import com.aus.notelikeus.data.sync.NoteSyncEngine
import com.aus.notelikeus.data.sync.ScheduledWorkOrigin
import com.aus.notelikeus.data.sync.ScheduledWorkOutcome
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Runs one queued cloud write.
 *
 * The WorkRequest carries the authority the command was queued with — the account **and** the
 * durable dataset epoch — because neither this worker nor the engine may re-derive it. Re-deriving
 * means reading the current session, or the current queue, and either of those is the dataset that
 * *replaced* the one the command came from: a delete queued for note 42 before a sign-out and a
 * sign-in would then run against whatever note 42 is now, with the old intent re-authorized as
 * current work.
 *
 * The check here is deliberately cheap and deliberately not the whole fence. It keeps obviously
 * dead work out of the engine entirely; the authoritative decision is
 * [com.aus.notelikeus.data.sync.LocalCommitGate.resolveScheduledOrigin], which compares the same
 * epoch and mints the executing token in one critical section — because a check on this side of a
 * suspension point can always be overtaken by an isolation on the other side.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {
    private val syncEngine: NoteSyncEngine by inject()
    private val sessionManager: CloudSessionManager by inject()
    private val epochAuthority: DatasetEpochAuthority by inject()

    override suspend fun doWork(): ListenableWorker.Result {
        val payload = readScheduledSyncPayload(inputData)
        if (payload == null) {
            // No origin authority: queued before epochs existed, or malformed. Nothing may be
            // assumed on its behalf, and there is nothing to retry — the current dataset's own
            // reconciliation is what recovers a missed write.
            return ListenableWorker.Result.success()
        }

        val outcome = ScheduledSyncDispatcher(
            syncEngine = syncEngine,
            epochAuthority = epochAuthority,
            currentUid = { sessionManager.getCurrentAccount().userId },
        ).dispatch(payload)

        return when (outcome) {
            // A refusal reports success on purpose: the command's dataset is gone, and asking
            // WorkManager to retry would retry the one operation that must not run.
            ScheduledWorkOutcome.Applied -> ListenableWorker.Result.success()
            ScheduledWorkOutcome.RefusedStaleOrigin -> ListenableWorker.Result.success()
            ScheduledWorkOutcome.Retryable,
            ScheduledWorkOutcome.Cancelled,
            -> if (runAttemptCount < MAX_RETRIES) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.failure()
            }
        }
    }

    companion object {
        const val WORK_TAG = "cloud_note_sync"
        const val KEY_NOTE_ID = "note_id"
        const val KEY_IS_DELETE = "is_delete"
        const val KEY_IS_RESTORE = "is_restore"
        const val KEY_EXPECTED_UID = "expected_uid"
        const val KEY_DATASET_EPOCH = "dataset_epoch"
        private const val MAX_RETRIES = 3
    }
}

/**
 * What one queued job asks for, as read from its WorkRequest.
 *
 * [expectedUid] and [datasetEpoch] are nullable only so that a job queued by a version that predates
 * epochs decodes to "no authority" rather than throwing; both are required before anything runs.
 */
data class ScheduledSyncPayload(
    val noteId: Long,
    val kind: PendingSyncKind,
    val expectedUid: String?,
    val datasetEpoch: DatasetEpoch?,
)

/**
 * Decodes a queued job's input, or `null` when it carries nothing that could authorize it.
 *
 * A missing note id is malformed rather than legacy, and a missing uid or epoch is work queued
 * before epochs existed. Both end the same way — the job completes without touching anything —
 * because "assume the current dataset" is the laundering this exists to prevent.
 */
fun readScheduledSyncPayload(inputData: Data): ScheduledSyncPayload? {
    val noteId = inputData.getLong(SyncWorker.KEY_NOTE_ID, -1L)
    if (noteId == -1L) return null

    val epochValue = inputData.getString(SyncWorker.KEY_DATASET_EPOCH)?.takeIf { it.isNotBlank() }
        ?: return null

    val kind = when {
        inputData.getBoolean(SyncWorker.KEY_IS_DELETE, false) -> PendingSyncKind.DELETE
        inputData.getBoolean(SyncWorker.KEY_IS_RESTORE, false) -> PendingSyncKind.RESTORE
        else -> PendingSyncKind.UPLOAD
    }

    return ScheduledSyncPayload(
        noteId = noteId,
        kind = kind,
        expectedUid = inputData.getString(SyncWorker.KEY_EXPECTED_UID)?.takeIf { it.isNotBlank() },
        datasetEpoch = DatasetEpoch(epochValue),
    )
}

/**
 * Hands one queued command to the engine, or abandons it without entering the engine at all.
 *
 * Split out of [SyncWorker] so the decision is testable without a live WorkManager: it is ordinary
 * suspend code taking plain values, and the worker is only the adapter that reads them off a
 * `WorkRequest`.
 *
 * The uid and epoch checks here are the *cheap* half of the fence. They stop obviously-dead work
 * before any I/O; they are not what makes the operation safe, because an isolation can land after
 * either one and before the engine's first statement. What makes it safe is that the engine's
 * scheduled entry re-resolves the same origin under `LocalCommitGate`, minting the executing token
 * in that same critical section — so an old command cannot be handed a token for the dataset that
 * replaced it.
 */
class ScheduledSyncDispatcher(
    private val syncEngine: NoteSyncEngine,
    private val epochAuthority: DatasetEpochAuthority,
    private val currentUid: suspend () -> String?,
) {
    suspend fun dispatch(payload: ScheduledSyncPayload): ScheduledWorkOutcome {
        val expectedUid = payload.expectedUid ?: return ScheduledWorkOutcome.RefusedStaleOrigin
        val epoch = payload.datasetEpoch ?: return ScheduledWorkOutcome.RefusedStaleOrigin

        // The epoch first: a command from a replaced dataset is refused without asking the session
        // anything, and the dataset — not the account — is what a same-uid sign-out/sign-in changes.
        // A device between datasets refuses here too, before the engine is entered at all: an
        // interrupted isolation must not let a queued job run while recovery has not finished.
        val authority = epochAuthority.awaitAuthority()
        if (authority !is DatasetAuthority.Stable || authority.epoch != epoch) {
            return ScheduledWorkOutcome.RefusedStaleOrigin
        }
        if (expectedUid != currentUid()) return ScheduledWorkOutcome.RefusedStaleOrigin

        val origin = ScheduledWorkOrigin(expectedUid = expectedUid, datasetEpoch = epoch)
        return when (payload.kind) {
            PendingSyncKind.DELETE -> syncEngine.deleteNoteFromScheduledWork(payload.noteId, origin)
            PendingSyncKind.RESTORE -> syncEngine.restoreNoteFromScheduledWork(payload.noteId, origin)
            PendingSyncKind.UPLOAD -> syncEngine.uploadNoteFromScheduledWork(payload.noteId, origin)
        }
    }
}
