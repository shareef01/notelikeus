package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.migration.AccountUidBridge
import com.aus.notelikeus.domain.platform.SyncCoordinator
import com.aus.notelikeus.domain.repository.NoteRepository
import kotlinx.coroutines.sync.withLock

/**
 * Wipes device-local notes, tombstones, and pending cloud work so a new Google account cannot
 * inherit the previous one's library — or apply its leftover tombstones to colliding ids.
 *
 * Sign-out always isolates (privacy policy: the next person at this device must not see the
 * previous account's notes). Sign-in and pull isolate only when [NoteSyncStateStore.lastMergedUserId]
 * is set and differs from the incoming uid, so a first-ever sign-in still uploads guest notes.
 *
 * ## Two phases, and why the order matters (R15.3)
 *
 * ```
 * beginDatasetIsolation()          durable: "an isolation from E1 to E2 is in flight"
 *   |                              from here, nothing scheduled may run — this process or the next
 *   v
 * LocalCommitGate.isolate {        the account boundary: the local generation advances here
 *     wipe local account-owned data
 *     completeDatasetIsolation()   durable: "this device is on E2"
 * }
 * ```
 *
 * The marker is written **before** the boundary, so every way this can be interrupted reads back as
 * "between datasets" and refuses. The alternative is what F-4C was: a failed epoch write left the
 * previous dataset looking current after the generation had already moved, so its queued work stayed
 * executable, in this process and in the next one.
 *
 * The wipe runs **before** the completion write, so there is no window in which the store says "on
 * the new dataset" while the old dataset's rows are still on disk. Both the wipe and the completion
 * are re-runnable, which is what lets recovery be the same operation as a first attempt.
 */
class LocalAccountIsolator(
    private val noteRepository: NoteRepository,
    private val syncStateStore: NoteSyncStateStore,
    private val syncCoordinator: SyncCoordinator,
    private val accountUidBridge: AccountUidBridge = AccountUidBridge(syncStateStore),
    /**
     * Hands the incoming account whatever a signed-out session staged for it.
     *
     * Injected as a function rather than an `AttachmentSyncService`, because that service already
     * depends on the sync engine this class is wired into; taking the type directly would close a
     * dependency cycle. Defaulted to a no-op so the many constructions that have nothing to adopt
     * — tests, platforms without attachments — stay unchanged.
     */
    private val adoptGuestStagedAttachments: suspend (String) -> Unit = {},
    /**
     * Drops everything held in memory for the dataset being left.
     *
     * Two things live there and both describe the departing generation: attachment bytes staged for its
     * notes, so a session's images do not outlive it, and the cloud revisions it learned, which is
     * D11/F-9 — the replacement library reuses note ids, so a revision remembered for the previous
     * generation's note 42 would otherwise be sent as this generation's base revision and make optimistic
     * concurrency approve or reject the wrong write. It matters most when the uid does *not* change.
     *
     * Injected for the same reason as the adoption hook above, and defaulted the same way.
     */
    private val clearDatasetScopedInMemoryState: suspend () -> Unit = {},
) {
    suspend fun isolate() {
        // Phase 1 — the durable transition marker, outside the gate. If this write fails, nothing has
        // happened: no boundary is crossed, no generation moves, and the device is simply still on the
        // dataset it was on. That is why it is deliberately *not* inside the isolate block — a failure
        // in there would leave the generation advanced with the old dataset still current, which is
        // exactly F-4C.
        syncCoordinator.beginDatasetIsolation()

        // Phase 2 — the account boundary. The generation is invalidated at the START of this held
        // section, so every token captured before it is refused from that moment on. Combined with the
        // commit running under the same gate, only two orders exist and both are safe:
        //   commit first  -> it lands on the old dataset, which this block then wipes;
        //   isolate first -> the stale commit finds a different generation and is refused.
        // See D8/D11 in docs/AUDIT_DEEPSEEK_2026-09-20.md.
        LocalCommitGate.isolate {
            wipeLocalAccountData()
            // Completed last, and only after the wipe: the store must never claim the new dataset
            // before the old one's rows are gone. A crash before this line leaves the transition
            // marked, which refuses everything until recovery finishes it. Cancelling the platform's
            // in-flight jobs happens inside this call and is cleanup on top, not the fence — a job
            // that survives physically still has to pass the epoch check first.
            syncCoordinator.completeDatasetIsolation()
        }
    }

    /**
     * Completes an isolation that was interrupted, if there is one.
     *
     * Called at startup and ahead of any sign-in decision, so a device left between datasets resolves
     * instead of staying quarantined forever: the wipe is idempotent and the completion is the same
     * write a first attempt would have made. Until it runs, nothing scheduled can execute — the
     * transition is durable — so this is promptness, not the fence.
     */
    suspend fun recoverIncompleteIsolationIfAny() {
        if (syncCoordinator.isDatasetIsolationIncomplete()) {
            isolate()
        }
    }

    /**
     * The account-owned local state of the dataset being left.
     *
     * Idempotent on purpose: recovery re-runs it, and a second pass over already-empty tables is the
     * cheap way to guarantee that a crash *inside* the wipe is re-entered rather than half-applied.
     */
    private suspend fun wipeLocalAccountData() {
        // Cancel in-flight workers before dropping rows they would otherwise upload as the new uid.
        syncCoordinator.clearPending()
        syncStateStore.clear()
        noteRepository.clearAllUserData()
        // First, before anything fallible: the moment the generation moves, the previous dataset's
        // in-memory state -- staged bytes and learned revisions -- must already be unreachable. Doing it
        // after a wipe that then failed would leave that state live in a generation already replacing the
        // dataset, and recovery re-runs this block, so the clear is re-entered rather than skipped.
        // Notes and staged bytes are gone from disk by the time the rest of the wipe finishes; the
        // in-memory image cache has to go too, or decrypted picture bytes outlive the session entitled
        // to them.
        clearDatasetScopedInMemoryState()
    }

    suspend fun isolateIfAccountChanged(incomingUid: String) {
        // An isolation that began and could not finish is completed before anything else is decided.
        // Neither "the same account is signing back in" nor "this is a first sign-in" applies while
        // the device is between datasets: the rows on disk belong to the dataset being left, and its
        // queued work must not be rescued by a matching uid.
        if (syncCoordinator.isDatasetIsolationIncomplete()) {
            isolate()
            return
        }

        val last = syncStateStore.lastMergedUserId()
        if (last != null && !accountUidBridge.accountsMatch(last, incomingUid)) {
            isolate()
            return
        }
        // Reached when there is no different previous account — a first-ever sign-in keeping the
        // guest library, or the same account signing back in. There is NO account boundary here, so
        // the generation must not move: adopting guest data into the first account is not an
        // isolation, and treating it as one would strand every edit captured moments earlier.
        //
        adoptGuestStagedAttachments(incomingUid)
    }
}
