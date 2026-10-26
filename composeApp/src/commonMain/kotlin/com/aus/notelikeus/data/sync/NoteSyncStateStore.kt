package com.aus.notelikeus.data.sync

/**
 * Device-local store for delete tombstones, known cloud-note ids, restore
 * markers, and the reconciliation high-water mark.
 *
 * Platform implementations vary (SharedPreferences on Android, DataStore or
 * file-based on desktop); the engine only depends on this interface.
 */
interface NoteSyncStateStore {

    /**
     * Marks [noteId] as deleted with the given [deletedAt] epoch-millis.
     * Optionally atomically records the server revision known at intent time.
     */
    fun markDeleted(noteId: Long, deletedAt: Long = currentTimeMillis(), baselineRevision: Long? = null)

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

    /** Removes restore markers for [ids] after the live remote note is confirmed. */
    fun clearRestored(ids: Collection<Long>)

    /**
     * Notes whose server deletion succeeded but R2/metadata GC has not.
     * Survives process death so later reconciliation can retry blob cleanup.
     */
    fun markPendingAttachmentGc(noteId: Long, attachmentIds: Collection<String> = emptyList())

    fun pendingAttachmentGcIds(): Set<Long>

    fun pendingAttachmentGcEntries(): Map<Long, Set<String>>

    fun clearPendingAttachmentGc(noteId: Long)

    // ---- recommits ----

    /**
     * Notes whose row took an attachment upload after the push that carried them, so no commit has
     * named that upload yet. Its server row stays provisional, and so out of hydration, until a push
     * of the note names it; a download that ran first would let the `r2:` reference go and lose the
     * image. Survives process death, so a sync cut off before that push makes it next time, ahead
     * of any download.
     */
    fun markRecommitNeeded(noteId: Long)

    fun recommitNeededIds(): Set<Long>

    fun clearRecommitNeeded(ids: Collection<Long>)

    // ---- reconciliation ----

    /** High-water mark (note timestamp) for the last successful reconcile. */
    fun lastReconciledAt(): Long

    fun markReconciled(at: Long)

    // ---- known cloud ids ----

    /** Note ids that were present in the cloud at the end of the last download. */
    fun knownCloudIds(): Set<Long>

    fun setKnownCloudIds(ids: Set<Long>)

    // ---- known server revisions ----

    fun knownServerRevisionById(): Map<Long, Long>

    fun updateKnownServerRevision(noteId: Long, revision: Long)

    fun updateKnownServerRevisions(revisions: Map<Long, Long>)

    fun clearKnownServerRevisions(noteIds: Collection<Long>)

    // ---- synced-version baselines ----

    /**
     * Note id → the client `timestamp` of the version this device last knew to be identical to the
     * cloud's. A row whose timestamp differs holds an edit the cloud has not confirmed; see
     * [ConflictResolution]. Equality, not ordering, so a wrong device clock cannot fake or hide one.
     */
    fun syncedTimestampById(): Map<Long, Long>

    fun updateSyncedTimestamps(timestamps: Map<Long, Long>)

    fun clearSyncedTimestamps(noteIds: Collection<Long>)

    // ---- delete fingerprints ----

    /**
     * Note id → [ConflictResolution.authoredContentDigest] of the words deleted on this device. Used
     * only when no baseline revision was known at delete time: a download may then complete the
     * delete at the revision it observes, but only if the cloud copy still holds exactly those words.
     */
    fun deletedDigestById(): Map<Long, String>

    fun setDeletedDigest(noteId: Long, digest: String)

    fun clearDeletedDigests(noteIds: Collection<Long>)

    // ---- delete baselines ----

    fun baselineDeleteRevisionById(): Map<Long, Long>

    fun setBaselineDeleteRevision(noteId: Long, revision: Long)

    fun clearBaselineDeleteRevision(noteId: Long)

    // ---- account guard ----

    /**
     * The uid that was recorded after the last successful merge/download.
     * Used by [reconcileUploads] to detect an account-switch race.
     */
    fun lastMergedUserId(): String?

    fun setLastMergedUserId(userId: String)

    // ---- cloud wipe generation ----

    /**
     * The account's cloud wipe generation that this device has acknowledged. 0 = never wiped.
     *
     * The server increments it inside `delete_all_user_cloud_data()` and refuses any note or
     * tombstone write that does not present the current value, so a device that was offline across
     * a wipe cannot put the deleted notes back. This is the value the transport presents; it is
     * durable because a process restart must not make the device forget what it already knew.
     */
    fun wipeGeneration(): Long

    fun setWipeGeneration(generation: Long)

    /**
     * True while this device must not upload its local notes: the account's cloud data was deleted
     * and the user has not yet decided whether these notes go up again.
     *
     * Uploading before that decision is indistinguishable, server-side, from a stale device undoing
     * the wipe — which is why the block exists and why only
     * [allowUploadsAfterCloudWipe] lifts it.
     */
    fun uploadsBlockedAfterCloudWipe(): Boolean

    /**
     * Records a wipe this device has not acknowledged. Nothing is uploaded and nothing is deleted
     * until the user decides, so this is where a wipe stops being able to reverse itself.
     */
    fun blockUploadsAfterCloudWipe()

    /**
     * The user's decision to upload these notes into the now-empty cloud.
     *
     * Clears the block *and* [setKnownCloudIds] is expected to be cleared by the caller: while the
     * ids are recorded as known, their absence from the cloud is read as "deleted elsewhere" and
     * the notes would be removed instead of recreated.
     */
    fun allowUploadsAfterCloudWipe()

    /** Wipes all stored state (used on sign-out). */
    fun clear()

    /** Provided by the platform so tests can stub it. */
    fun currentTimeMillis(): Long

    companion object {
        /** 180 days — tombstones older than this are pruned. */
        const val TOMBSTONE_TTL_MS: Long = 180L * 24 * 60 * 60 * 1000
    }
}
