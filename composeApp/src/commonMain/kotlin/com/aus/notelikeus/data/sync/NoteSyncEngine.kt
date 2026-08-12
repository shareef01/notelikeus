package com.aus.notelikeus.data.sync

import com.aus.notelikeus.data.local.dao.LabelDao
import com.aus.notelikeus.data.local.dao.NoteDao
import com.aus.notelikeus.data.local.entity.NoteLabelCrossRef
import com.aus.notelikeus.data.mapper.toChecklistItemEntity
import com.aus.notelikeus.data.mapper.toLabel
import com.aus.notelikeus.data.mapper.toNote
import com.aus.notelikeus.data.mapper.toNoteEntity
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.util.DateUtils

/**
 * The cloud returned no notes for an account that is known to have them.
 *
 * A fetch that fails open — an expired token, a truncated page, an offline transport returning a
 * default — loses data in both directions. [NoteSyncEngine.downloadAllNotes] treats an absent note
 * as deleted-elsewhere and removes the local copy; [NoteSyncEngine.uploadAllNotes] reads the same
 * empty result as "no remote is newer" and pushes every local note over the cloud copy. Failing the
 * sync is the safe answer in both; the next successful one reconciles normally.
 */
class SuspectEmptyCloudException(
    val knownCloudNoteCount: Int
) : Exception(
    "Cloud returned no notes but $knownCloudNoteCount were expected — refusing to delete local " +
        "copies. Check the connection or sign in again."
)

/**
 * Policy-only cloud-sync engine.
 *
 * Broken cycle: NoteSyncEngine now depends on DAOs instead of the high-level Repository,
 * which in turn depends on the SyncCoordinator (implementing this engine).
 */
class NoteSyncEngine(
    private val transport: CloudNoteTransport,
    private val noteDao: NoteDao,
    private val labelDao: LabelDao,
    private val syncStateStore: NoteSyncStateStore,
    private val uidProvider: suspend () -> Result<String>,
    private val platform: String = "android"
) {

    suspend fun uploadAllNotes(): Result<Int> {
        return runCatching {
            val uid = uidProvider().getOrThrow()
            mergeCloudTombstones(uid)
            val localNotes = noteDao.getAllNotesForBackup().map { it.toNote() }
            purgeLocalTombstonedNotes(localNotes)
            val notes = localNotes.filter { note ->
                val id = note.id ?: return@filter false
                !syncStateStore.isDeleted(id)
            }

            if (notes.isEmpty()) {
                transport.writeSyncMeta(uid, 0, platform)
                return@runCatching 0
            }

            val remoteRecords = transport.fetchNotes(uid)

            // The same hazard downloadAllNotes guards against, pointing the other way. Here an
            // empty fetch does not delete anything directly — it empties the timestamp maps below,
            // and cloudWinsConflict reads a null remote timestamp as "local wins". Every local note
            // would then be pushed over whatever the cloud actually holds, discarding newer edits
            // made on another device. A transport that fails open (Android's Firestore get() falls
            // back to an empty cached snapshot rather than throwing) makes that reachable, so refuse
            // the sync instead; the next successful one reconciles normally.
            if (syncStateStore.lastMergedUserId() == uid) {
                val knownCloudIds = syncStateStore.knownCloudIds()
                if (remoteRecords.isEmpty() && knownCloudIds.isNotEmpty()) {
                    throw SuspectEmptyCloudException(knownCloudIds.size)
                }
            }

            val remoteTimestamps = remoteRecords.associate { it.noteId to (it.clientTimestamp ?: 0L) }
            val remoteServerTimestamps = remoteRecords.associate { it.noteId to it.serverUpdatedAt }

            var uploaded = 0
            val toPush = mutableListOf<Note>()

            for (note in notes) {
                val noteId = note.id ?: continue
                val remoteServerTs = remoteServerTimestamps[noteId]
                val remoteTs = remoteTimestamps[noteId]
                if (cloudWinsConflict(remoteServerTs, note.serverUpdatedAt, remoteTs, note.timestamp)) {
                    continue
                }
                toPush.add(note)
            }

            if (toPush.isNotEmpty()) {
                val serverTimestamps = transport.putNotes(uid, toPush)
                uploaded = toPush.size
                for ((noteId, resolvedTs) in serverTimestamps) {
                    if (resolvedTs != null) {
                        noteDao.updateServerTimestamp(noteId, resolvedTs)
                    }
                }
            }

            transport.writeSyncMeta(uid, noteDao.getCloudEligibleNoteCount(), platform)
            uploaded
        }
    }

    suspend fun reconcileUploads(): Result<Int> {
        return runCatching {
            val uid = uidProvider().getOrThrow()
            if (syncStateStore.lastMergedUserId() != uid) {
                return@runCatching 0
            }
            mergeCloudTombstones(uid)
            val localNotes = noteDao.getAllNotesForBackup().map { it.toNote() }
            purgeLocalTombstonedNotes(localNotes)

            val since = syncStateStore.lastReconciledAt()
            val highWater = DateUtils.currentTimeMillis()
            val changed = localNotes.filter { note ->
                val id = note.id ?: return@filter false
                note.timestamp > since && !syncStateStore.isDeleted(id)
            }

            // Belongs to this account: the lastMergedUserId check above already returned otherwise.
            val knownCloudIds = syncStateStore.knownCloudIds()

            var uploaded = 0
            for (note in changed) {
                val noteId = note.id ?: continue
                val remote = transport.fetchNote(uid, noteId)
                // A null remote usually means "never synced", the legitimate answer for a new note,
                // so this cannot key off null alone the way the collection-level guards do. But when
                // the last full download recorded this id as present in the cloud and no tombstone
                // has since explained its absence — mergeCloudTombstones ran above, and a genuine
                // remote delete leaves one — a failed-open fetch is likelier than the document
                // vanishing. Pushing on that null would resolve the conflict in local's favour and
                // overwrite whatever is actually there.
                if (remote == null && noteId in knownCloudIds) {
                    throw SuspectEmptyCloudException(knownCloudIds.size)
                }
                val remoteServerTs = remote?.serverUpdatedAt
                val localServerTs = note.serverUpdatedAt
                val remoteTs = remote?.clientTimestamp
                if (cloudWinsConflict(remoteServerTs, localServerTs, remoteTs, note.timestamp)) {
                    continue
                }
                putNote(uid, note)
                uploaded++
            }

            transport.writeSyncMeta(uid, noteDao.getCloudEligibleNoteCount(), platform)
            syncStateStore.markReconciled(highWater)
            uploaded
        }
    }

    suspend fun uploadNote(noteId: Long): Result<Unit> {
        return runCatching {
            val uid = uidProvider().getOrThrow()
            refreshCloudTombstone(uid, noteId)
            if (syncStateStore.isDeleted(noteId)) {
                return deleteNote(noteId)
            }
            val note = noteDao.getNoteById(noteId)?.toNote()
                ?: return@runCatching
            val remote = transport.fetchNote(uid, noteId)
            if (remote != null) {
                val remoteServerTs = remote.serverUpdatedAt
                val remoteTs = remote.clientTimestamp
                if (cloudWinsConflict(remoteServerTs, note.serverUpdatedAt, remoteTs, note.timestamp)) {
                    return@runCatching
                }
            }
            putNote(uid, note)
        }
    }

    suspend fun restoreNote(noteId: Long): Result<Unit> {
        return runCatching {
            syncStateStore.clearDeleted(listOf(noteId))
            val uid = uidProvider().getOrThrow()
            transport.deleteTombstones(uid, listOf(noteId))
            syncStateStore.clearRestored(listOf(noteId))
            val note = noteDao.getNoteById(noteId)?.toNote()
                ?: return@runCatching
            putNote(uid, note)
        }
    }

    suspend fun deleteNote(noteId: Long): Result<Unit> {
        return runCatching {
            val deletedAt = DateUtils.currentTimeMillis()
            syncStateStore.markDeleted(noteId, deletedAt)
            val uid = uidProvider().getOrThrow()
            transport.writeTombstone(uid, noteId, deletedAt)
            transport.deleteNotes(uid, listOf(noteId))
        }
    }

    suspend fun downloadAllNotes(): Result<Int> {
        return runCatching {
            val uid = uidProvider().getOrThrow()
            val cloudTombstones = mergeCloudTombstones(uid)
            val localNotesBeforePurge = noteDao.getAllNotesForBackup().map { it.toNote() }
            val purgedIds = purgeLocalTombstonedNotes(localNotesBeforePurge)
            var changes = purgedIds.size
            val remoteRecords = transport.fetchNotes(uid)

            val labelMap = labelDao.getAllLabelsOnce()
                .associateBy { it.name.lowercase() }
                .toMutableMap()

            suspend fun ensureLabel(name: String): Label {
                val key = name.trim().lowercase()
                labelMap[key]?.let { return it.toLabel() }
                val id = labelDao.insertLabel(com.aus.notelikeus.data.local.entity.LabelEntity(name = name.trim()))
                val label = com.aus.notelikeus.data.local.entity.LabelEntity(id = id, name = name.trim())
                labelMap[key] = label
                return label.toLabel()
            }

            val cloudNoteIds = mutableSetOf<Long>()
            // Locally-winning notes are collected and sent in one batch at the end. Pushing each
            // one as its own single-note commit inside the loop cost a round trip per note, while
            // uploadAllNotes batched the identical work.
            val toPushBack = mutableListOf<Note>()
            val allLocalNotes = localNotesBeforePurge.filter { note -> note.id !in purgedIds }
            val localNotesById = allLocalNotes.mapNotNull { note -> note.id?.let { it to note } }.toMap()

            // knownCloudIds belongs to whichever account last completed a download. Carrying it
            // across an account switch would read the new account's (legitimately empty) cloud as
            // "the previous account's notes were deleted" and remove them from this device.
            val isSameAccountAsLastMerge = syncStateStore.lastMergedUserId() == uid
            val previouslyKnownCloudIds =
                if (isSameAccountAsLastMerge) syncStateStore.knownCloudIds() else emptySet()

            // A whole collection vanishing is far more often a failed fetch than a real deletion:
            // a genuine remote delete leaves tombstones, which mergeCloudTombstones has already
            // applied above. Refuse to reconcile rather than delete notes on a bad read.
            if (isSameAccountAsLastMerge &&
                remoteRecords.isEmpty() &&
                previouslyKnownCloudIds.isNotEmpty()
            ) {
                throw SuspectEmptyCloudException(previouslyKnownCloudIds.size)
            }

            for (record in remoteRecords) {
                val noteId = record.noteId

                if (syncStateStore.isDeleted(noteId)) {
                    transport.deleteNotes(uid, listOf(noteId))
                    val deletedAt = syncStateStore.deletedAtById()[noteId] ?: DateUtils.currentTimeMillis()
                    transport.writeTombstone(uid, noteId, deletedAt)
                    changes++
                    continue
                }

                cloudNoteIds.add(noteId)
                val cloudNote = record.toNote(::ensureLabel)
                val localNote = localNotesById[noteId]

                val cloudWins = if (localNote == null) {
                    true
                } else {
                    cloudWinsConflict(
                        record.serverUpdatedAt,
                        localNote.serverUpdatedAt,
                        record.clientTimestamp,
                        localNote.timestamp,
                    )
                }

                when {
                    localNote == null -> {
                        innerInsert(cloudNote)
                        changes++
                    }
                    cloudWins -> {
                        innerUpdate(cloudNote)
                        changes++
                    }
                    else -> {
                        toPushBack += localNote
                        changes++
                    }
                }
            }

            for (localNote in allLocalNotes) {
                val noteId = localNote.id ?: continue
                if (noteId in cloudNoteIds) continue
                if (syncStateStore.isDeleted(noteId)) continue

                if (noteId in previouslyKnownCloudIds) {
                    noteDao.deleteNote(localNote.toNoteEntity())
                    val deletedAt = DateUtils.currentTimeMillis()
                    syncStateStore.markDeleted(noteId, deletedAt)
                    transport.writeTombstone(uid, noteId, deletedAt)
                    changes++
                    continue
                }

                toPushBack += localNote
                changes++
            }

            putNotes(uid, toPushBack)

            syncStateStore.setKnownCloudIds(cloudNoteIds)
            pruneExpiredTombstones(uid, cloudNoteIds, cloudTombstones)
            transport.writeSyncMeta(uid, noteDao.getCloudEligibleNoteCount(), platform)
            syncStateStore.setLastMergedUserId(uid)

            changes
        }
    }

    suspend fun deleteAllCloudData(): Result<Int> {
        return runCatching {
            val uid = uidProvider().getOrThrow()
            val records = transport.fetchNotes(uid)
            val noteIds = records.map { it.noteId }
            transport.deleteNotes(uid, noteIds)
            val tombstones = transport.fetchTombstones(uid)
            transport.deleteTombstones(uid, tombstones.keys.toList())
            transport.deleteSyncMeta(uid)
            syncStateStore.clear()
            noteIds.size
        }
    }

    internal fun cloudWinsConflict(
        remoteServerUpdatedAt: Long?,
        localServerUpdatedAt: Long?,
        remoteClientTimestamp: Long?,
        localClientTimestamp: Long,
    ): Boolean {
        if (remoteServerUpdatedAt != null && localServerUpdatedAt != null) {
            if (remoteServerUpdatedAt != localServerUpdatedAt) {
                return remoteServerUpdatedAt > localServerUpdatedAt
            }
            return remoteClientTimestamp != null && remoteClientTimestamp >= localClientTimestamp
        }
        return remoteClientTimestamp != null && remoteClientTimestamp > localClientTimestamp
    }

    private suspend fun putNote(uid: String, note: Note) {
        putNotes(uid, listOf(note))
    }

    /** Uploads [notes] in one transport call and records the server timestamps it returns. */
    private suspend fun putNotes(uid: String, notes: List<Note>) {
        if (notes.isEmpty()) return
        val timestamps = transport.putNotes(uid, notes)
        for ((noteId, resolved) in timestamps) {
            if (resolved != null) {
                noteDao.updateServerTimestamp(noteId, resolved)
            }
        }
    }

    /** Returns the cloud tombstones that survived the merge, so callers need not re-fetch them. */
    private suspend fun mergeCloudTombstones(uid: String): Map<Long, Long> {
        val remote = transport.fetchTombstones(uid)
        val restored = syncStateStore.restoredIds()
        val stale = remote.keys.filter { it in restored }
        if (stale.isNotEmpty()) {
            transport.deleteTombstones(uid, stale)
            syncStateStore.clearRestored(stale)
        }
        val live = remote.filterKeys { it !in restored }
        syncStateStore.mergeDeleted(live)
        return live
    }

    private suspend fun refreshCloudTombstone(uid: String, noteId: Long) {
        if (noteId in syncStateStore.restoredIds()) return
        val tombstones = transport.fetchTombstones(uid)
        val deletedAt = tombstones[noteId] ?: return
        syncStateStore.mergeDeleted(mapOf(noteId to deletedAt))
    }

    private suspend fun purgeLocalTombstonedNotes(localNotes: List<Note>): Set<Long> {
        val purged = mutableSetOf<Long>()
        for (note in localNotes) {
            val id = note.id ?: continue
            if (!syncStateStore.isDeleted(id)) continue
            noteDao.deleteNote(note.toNoteEntity())
            purged.add(id)
        }
        return purged
    }

    /**
     * @param cloud tombstones already fetched by [mergeCloudTombstones] earlier in this sync.
     *   Any tombstone written since is dated now and cannot be expired, so re-reading the
     *   collection here only bought a second round trip.
     */
    private suspend fun pruneExpiredTombstones(
        uid: String,
        liveNoteIds: Set<Long>,
        cloud: Map<Long, Long>
    ) {
        val pruned = syncStateStore.pruneExpired(NoteSyncStateStore.TOMBSTONE_TTL_MS)
            .filter { it !in liveNoteIds }
        if (pruned.isNotEmpty()) {
            transport.deleteTombstones(uid, pruned.toList())
        }
        val now = DateUtils.currentTimeMillis()
        val expiredRemote = cloud.mapNotNull { (noteId, deletedAt) ->
            if (noteId in liveNoteIds) return@mapNotNull null
            if (now - deletedAt >= NoteSyncStateStore.TOMBSTONE_TTL_MS) noteId else null
        }
        if (expiredRemote.isNotEmpty()) {
            transport.deleteTombstones(uid, expiredRemote)
        }
        syncStateStore.clearDeleted(expiredRemote)
    }

    private suspend fun innerInsert(note: Note) {
        val insertedId = noteDao.insertNote(note.toNoteEntity())
        note.labels.forEach { label ->
            label.id?.let { labelId ->
                noteDao.insertNoteLabelCrossRef(NoteLabelCrossRef(insertedId, labelId))
            }
        }
        note.checklist.forEach { item ->
            noteDao.insertChecklistItem(item.toChecklistItemEntity(insertedId))
        }
    }

    private suspend fun innerUpdate(note: Note) {
        val noteId = note.id ?: return
        noteDao.updateNote(note.toNoteEntity())
        
        noteDao.deleteNoteLabelCrossRefs(noteId)
        note.labels.forEach { label ->
            label.id?.let { labelId ->
                noteDao.insertNoteLabelCrossRef(NoteLabelCrossRef(noteId, labelId))
            }
        }

        noteDao.deleteChecklistItems(noteId)
        note.checklist.forEach { item ->
            noteDao.insertChecklistItem(item.toChecklistItemEntity(noteId))
        }
    }
}

internal suspend fun CloudNoteRecord.toNote(
    resolveLabel: suspend (String) -> Label
): Note = Note(
    id = noteId,
    title = title,
    content = content,
    timestamp = timestamp,
    color = color,
    isPinned = isPinned,
    isArchived = isArchived,
    isTrashed = isTrashed,
    position = position,
    reminderTimestamp = reminderTimestamp,
    serverUpdatedAt = serverUpdatedAt,
    labels = labels.map { name -> resolveLabel(name) },
    attachments = emptyList(),
    checklist = checklistItems.mapIndexed { index, item ->
        ChecklistItem(
            text = item.text,
            isChecked = item.isChecked,
            position = item.position
        )
    }
)
