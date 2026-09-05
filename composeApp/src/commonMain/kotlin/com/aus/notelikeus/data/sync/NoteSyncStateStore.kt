package com.aus.notelikeus.data.sync

/**
 * Device-local store for delete tombstones, known cloud-note ids, restore
 * markers, and the reconciliation high-water mark.
 *
 * Platform implementations vary (SharedPreferences on Android, DataStore or
 * file-based on desktop); the engine only depends on this interface.
 */
interface NoteSyncStateStore {

    /** Marks [noteId] as deleted with the given [deletedAt] epoch-millis. */
    fun markDeleted(noteId: Long, deletedAt: Long = currentTimeMillis())

    /**
     * Merges cloud tombstones into the local store. When a note already has
     * a local tombstone, the older [deletedAt] is kept (the first delete is
     * the one that matters for TTL expiry).
     */
    fun mergeDeleted(entries: Map<Long, Long>)

    /** True when [noteId] has an active local tombstone. */
    fun isDeleted(noteId: Long): Boolean

    /** Every currently-tombstoned note id. */
    fun deletedIds(): Set<Long>

    /** Every tombstone as noteId → deletedAt. */
    fun deletedAtById(): Map<Long, Long>

    /**
     * Removes tombstones older than [maxAgeMs] and returns the pruned ids.
     * The caller is responsible for also deleting the corresponding cloud
     * tombstones.
     */
    fun pruneExpired(maxAgeMs: Long, now: Long = currentTimeMillis()): Set<Long>

    /** Removes tombstones for [ids] (used after a restore or purge). */
    fun clearDeleted(ids: Collection<Long>)

    // ---- restore markers ----

    /**
     * Marks [noteId] as "restore in progress" — the restore has cleared the
     * local tombstone but the cloud tombstone may not be gone yet.
     * Survives process death so a later sync can finish the cleanup.
     */
    fun markRestored(noteId: Long)

    /** Every note id currently marked as restored. */
    fun restoredIds(): Set<Long>

    /** Removes restore markers for [ids] (cloud tombstone confirmed gone). */
    fun clearRestored(ids: Collection<Long>)

    // ---- reconciliation ----

    /** High-water mark (note timestamp) for the last successful reconcile. */
    fun lastReconciledAt(): Long

    fun markReconciled(at: Long)

    // ---- known cloud ids ----

    /** Note ids that were present in the cloud at the end of the last download. */
    fun knownCloudIds(): Set<Long>

    fun setKnownCloudIds(ids: Set<Long>)

    // ---- account guard ----

    /**
     * The uid that was recorded after the last successful merge/download.
     * Used by [reconcileUploads] to detect an account-switch race.
     */
    fun lastMergedUserId(): String?

    fun setLastMergedUserId(userId: String)

    /** Wipes all stored state (used on sign-out). */
    fun clear()

    /** Provided by the platform so tests can stub it. */
    fun currentTimeMillis(): Long

    companion object {
        /** 180 days — tombstones older than this are pruned. */
        const val TOMBSTONE_TTL_MS: Long = 180L * 24 * 60 * 60 * 1000
    }
}
