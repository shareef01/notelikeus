package com.aus.notelikeus.data.remote

import com.aus.notelikeus.domain.platform.PendingSyncKind
import android.content.Context
import android.content.SharedPreferences
import com.aus.notelikeus.data.sync.DatasetEpoch
import com.aus.notelikeus.data.sync.DatasetEpochStore
import com.aus.notelikeus.data.sync.DatasetPending
import com.aus.notelikeus.data.sync.PendingSyncCommand
import com.aus.notelikeus.data.sync.decodePendingSyncCommand
import com.aus.notelikeus.data.sync.encodeForStorage

/**
 * Survives process death for the delayed cloud sync queue — and for the dataset epoch the queue
 * belongs to.
 *
 * The epoch is stored beside the queue rather than in a store of its own, because the two have to
 * move together: a rotation persists the new epoch and drops the old dataset's commands in **one**
 * commit, so no crash can leave a rotated epoch sitting next to work it does not own. Splitting them
 * across two preferences files would reintroduce exactly the window the ordering exists to close.
 *
 * Every queued entry carries its own epoch as well (`PendingSyncCommand.encodeForStorage`), so work
 * that somehow outlived its dataset is still refused where it is read.
 */
class PendingCloudSyncStore(
    context: Context
) : DatasetEpochStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): DatasetPending {
        val epoch = prefs.getString(KEY_EPOCH, null)?.takeIf { it.isNotBlank() }?.let(::DatasetEpoch)
        val commands = PendingSyncKind.entries.flatMap { kind ->
            prefs.getStringSet(keyFor(kind), emptySet())
                .orEmpty()
                .mapNotNull(::decodePendingSyncCommand)
                .filter { it.kind == kind }
        }
        return DatasetPending(
            epoch = epoch,
            ownerUid = prefs.getString(KEY_OWNER_UID, null)?.takeIf { it.isNotBlank() },
            commands = commands,
            // An in-flight isolation is part of the same record, so a crash cannot separate "the
            // device is between datasets" from the queue that state has to refuse.
            isolationTarget = prefs.getString(KEY_ISOLATION_TARGET, null)
                ?.takeIf { it.isNotBlank() }
                ?.let(::DatasetEpoch),
        )
    }

    /**
     * One `commit()` for the whole record — epoch, owner, isolation marker and every queue.
     *
     * **Failure surface, precisely.** `commit()` is the blocking form and documents its `false` return
     * as "the new values were not successfully written", so the old record remains; [check] turns that
     * into a thrown [IllegalStateException]. This method has **no suspension points**, so a coroutine
     * cannot be cancelled inside it and no failure can be observed *after* a successful commit: here,
     * a failure really does mean nothing was written. That is a property of this implementation, not of
     * [DatasetEpochStore] — desktop's `DataStore.edit` can report a failure after its file swap — which
     * is why the authority reconciles a failed marker write by reading the record back instead of
     * trusting the exception.
     */
    override suspend fun save(pending: DatasetPending) {
        val editor = prefs.edit()
        editor.putString(KEY_EPOCH, pending.epoch?.value)
        editor.putString(KEY_OWNER_UID, pending.ownerUid)
        editor.putString(KEY_ISOLATION_TARGET, pending.isolationTarget?.value)
        for (kind in PendingSyncKind.entries) {
            editor.putStringSet(
                keyFor(kind),
                pending.commandsOf(kind).map { it.encodeForStorage() }.toSet(),
            )
        }
        // `commit` rather than `apply`: the new epoch has to be durable before anything that could
        // still act on the old one is allowed to proceed, and `apply` only hands the write to a
        // background thread. The single commit is also what makes the rotation atomic — either the
        // new epoch and the emptied queue are both there, or neither is.
        check(editor.commit()) { "Failed to persist the pending cloud sync queue" }
    }

    private fun keyFor(kind: PendingSyncKind): String = when (kind) {
        PendingSyncKind.UPLOAD -> KEY_UPLOADS
        PendingSyncKind.DELETE -> KEY_DELETES
        PendingSyncKind.RESTORE -> KEY_RESTORES
    }

    private companion object {
        const val PREFS_NAME = "pending_cloud_sync"
        // The pre-R15.1 keys. They held bare note ids, which decode to nothing — the ids were
        // queued by a version that recorded no dataset, and stamping them with the current one is
        // the laundering this store now refuses. They are dropped on the first `save`.
        const val KEY_UPLOADS = "uploads"
        const val KEY_DELETES = "deletes"
        const val KEY_RESTORES = "restores"
        const val KEY_EPOCH = "dataset_epoch"
        const val KEY_OWNER_UID = "dataset_owner_uid"

        /** Non-blank while an isolation is in flight — see `DatasetPending.isolationTarget`. */
        const val KEY_ISOLATION_TARGET = "dataset_isolation_target"
    }
}
