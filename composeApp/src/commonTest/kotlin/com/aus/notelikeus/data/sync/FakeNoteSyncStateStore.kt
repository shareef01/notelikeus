package com.aus.notelikeus.data.sync

/**
 * In-memory [NoteSyncStateStore] for testing [NoteSyncEngine].
 */
class FakeNoteSyncStateStore : NoteSyncStateStore {

    private val deleted = mutableMapOf<Long, Long>()
    private val restored = mutableSetOf<Long>()
    private val pendingAttachmentGc = mutableMapOf<Long, MutableSet<String>>()
    private val knownCloud = mutableSetOf<Long>()
    private val knownServerRevisions = mutableMapOf<Long, Long>()
    private val baselineDeleteRevisions = mutableMapOf<Long, Long>()
    private var reconciledAt: Long = 0L
    private var mergedUserId: String? = null
    var currentTime: Long = 1_000_000L

    override fun markDeleted(noteId: Long, deletedAt: Long, baselineRevision: Long?) {
        if (noteId !in deleted) {
            deleted[noteId] = deletedAt
        }
        if (baselineRevision != null) {
            baselineDeleteRevisions[noteId] = baselineRevision
        }
    }

    override fun mergeDeleted(entries: Map<Long, Long>) {
        for ((id, deletedAt) in entries) {
            val existing = deleted[id]
            if (existing == null || deletedAt < existing) {
                deleted[id] = deletedAt
            }
        }
    }

    override fun isDeleted(noteId: Long): Boolean = noteId in deleted

    override fun deletedIds(): Set<Long> = deleted.keys

    override fun deletedAtById(): Map<Long, Long> = deleted.toMap()

    override fun pruneExpired(maxAgeMs: Long, now: Long): Set<Long> {
        val pruned = deleted.filter { (_, deletedAt) -> now - deletedAt >= maxAgeMs }.keys
        deleted.keys.removeAll(pruned)
        return pruned
    }

    override fun clearDeleted(ids: Collection<Long>) {
        deleted.keys.removeAll(ids.toSet())
    }

    override fun markRestored(noteId: Long) {
        restored.add(noteId)
    }

    override fun restoredIds(): Set<Long> = restored.toSet()

    override fun clearRestored(ids: Collection<Long>) {
        restored.removeAll(ids.toSet())
    }

    override fun markPendingAttachmentGc(noteId: Long, attachmentIds: Collection<String>) {
        pendingAttachmentGc.getOrPut(noteId) { mutableSetOf() }.addAll(attachmentIds)
    }

    override fun pendingAttachmentGcIds(): Set<Long> = pendingAttachmentGc.keys.toSet()

    override fun pendingAttachmentGcEntries(): Map<Long, Set<String>> =
        pendingAttachmentGc.mapValues { it.value.toSet() }

    override fun clearPendingAttachmentGc(noteId: Long) {
        pendingAttachmentGc.remove(noteId)
    }

    override fun lastReconciledAt(): Long = reconciledAt

    override fun markReconciled(at: Long) {
        reconciledAt = at
    }

    override fun knownCloudIds(): Set<Long> = knownCloud.toSet()

    override fun setKnownCloudIds(ids: Set<Long>) {
        knownCloud.clear()
        knownCloud.addAll(ids)
    }

    override fun knownServerRevisionById(): Map<Long, Long> = knownServerRevisions.toMap()

    override fun updateKnownServerRevision(noteId: Long, revision: Long) {
        knownServerRevisions[noteId] = revision
    }

    override fun updateKnownServerRevisions(revisions: Map<Long, Long>) {
        knownServerRevisions.putAll(revisions)
    }

    override fun clearKnownServerRevisions(noteIds: Collection<Long>) {
        knownServerRevisions.keys.removeAll(noteIds.toSet())
    }

    override fun baselineDeleteRevisionById(): Map<Long, Long> = baselineDeleteRevisions.toMap()

    override fun setBaselineDeleteRevision(noteId: Long, revision: Long) {
        baselineDeleteRevisions[noteId] = revision
    }

    override fun clearBaselineDeleteRevision(noteId: Long) {
        baselineDeleteRevisions.remove(noteId)
    }

    override fun lastMergedUserId(): String? = mergedUserId

    override fun setLastMergedUserId(userId: String) {
        mergedUserId = userId
    }

    override fun clear() {
        deleted.clear()
        restored.clear()
        pendingAttachmentGc.clear()
        knownCloud.clear()
        knownServerRevisions.clear()
        baselineDeleteRevisions.clear()
        reconciledAt = 0L
        mergedUserId = null
    }

    override fun currentTimeMillis(): Long = currentTime
}
