package com.aus.notelikeus.data.remote

import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private fun Timestamp.toEpochMillis(): Long = seconds * 1000 + nanoseconds / 1_000_000

@Singleton
class FirebaseNoteSync @Inject constructor(
    private val noteRepository: NoteRepository,
    private val sessionManager: FirebaseSessionManager,
    private val firestore: FirebaseFirestore,
    private val syncStateStore: NoteSyncStateStore
) {

    suspend fun uploadAllNotes(): Result<Int> {
        return try {
            val uid = sessionManager.ensureGoogleSignedIn().getOrThrow()
            mergeCloudTombstones(uid)
            val localNotes = noteRepository.getAllNotesForBackup()
            // Purge drops exactly the tombstoned notes the filter below already excludes, so the
            // single read above covers both.
            purgeLocalTombstonedNotes(localNotes)
            val notes = localNotes.filter { note ->
                val id = note.id ?: return@filter false
                note.isCloudSyncEligible() && !syncStateStore.isDeleted(id)
            }

            if (notes.isEmpty()) {
                updateSyncMeta(uid, 0)
                return Result.success(0)
            }

            val notesCollection = userNotesCollection(uid)
            val remoteDocs = notesCollection.get().await().documents
            val remoteTimestamps = remoteDocs.associate { doc ->
                val id = doc.id.toLongOrNull() ?: doc.getLong("localId") ?: -1L
                id to (doc.getLong("timestamp") ?: 0L)
            }
            // Server-assigned, so cross-device comparisons aren't at the mercy of either
            // device's clock. Falls back to the client `timestamp` map above only for a note
            // that hasn't round-tripped through sync since this field was introduced.
            val remoteServerTimestamps = remoteDocs.associate { doc ->
                val id = doc.id.toLongOrNull() ?: doc.getLong("localId") ?: -1L
                id to doc.getTimestamp("serverUpdatedAt")?.toEpochMillis()
            }

            var uploaded = 0
            notes.chunked(BATCH_LIMIT).forEach { chunk ->
                val batch = firestore.batch()
                var batchCount = 0
                val uploadedIds = mutableListOf<Long>()
                chunk.forEach { note ->
                    val noteId = note.id ?: return@forEach
                    val remoteServerTs = remoteServerTimestamps[noteId]
                    val localServerTs = note.serverUpdatedAt
                    val remoteIsNewer = if (remoteServerTs != null && localServerTs != null) {
                        remoteServerTs > localServerTs
                    } else {
                        val remoteTs = remoteTimestamps[noteId]
                        remoteTs != null && remoteTs > note.timestamp
                    }
                    if (remoteIsNewer) return@forEach
                    batch.set(
                        notesCollection.document(noteId.toString()),
                        note.toCloudMap(),
                        SetOptions.merge()
                    )
                    uploadedIds.add(noteId)
                    batchCount++
                }
                if (batchCount > 0) {
                    batch.commit().await()
                    uploaded += batchCount
                    // Same staleness reasoning as putCloudNote — refresh Room's cache for every
                    // note this chunk actually wrote, run concurrently since a chunk can be large.
                    coroutineScope {
                        uploadedIds
                            .map { noteId -> async { refreshServerTimestamp(notesCollection.document(noteId.toString()), noteId) } }
                            .awaitAll()
                    }
                }
            }

            updateSyncMeta(uid, notes.size)
            Result.success(uploaded)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    /**
     * Incremental upload path for the periodic reconciliation worker.
     *
     * [uploadAllNotes] reads the entire cloud collection every run to compare timestamps; on a
     * device with many notes that is a full-collection read every cycle, forever, even when
     * nothing changed. This variant still pulls cloud tombstones (so deletions made on other
     * devices propagate — that housekeeping must not be skipped), but only re-checks notes changed
     * locally since the last successful reconcile, reading just those few remote docs. An idle
     * device therefore pays only for the tombstone read.
     *
     * Upload-only and idempotent: a note missed here is still pushed by its per-note SyncWorker or
     * the next reconcile, so the worst case is a delayed upload, never lost local data.
     */
    suspend fun reconcileUploads(): Result<Int> {
        return try {
            val uid = sessionManager.ensureGoogleSignedIn().getOrThrow()
            // This worker runs independently of sign-in/sign-out, on its own 12h schedule, so it
            // cannot assume the local Room DB actually belongs to the currently signed-in uid. If
            // a different account signs in without a clean sign-out, MainViewModel wipes local
            // data before recording lastMergedUserId — a run that lands in that window would
            // otherwise upload the previous account's still-present local notes into this uid's
            // Firestore data. Bail out and let the next cycle (or the per-note SyncWorker path)
            // pick it up once the wipe has actually completed.
            if (syncStateStore.lastMergedUserId() != uid) {
                return Result.success(0)
            }
            mergeCloudTombstones(uid)
            val localNotes = noteRepository.getAllNotesForBackup()
            purgeLocalTombstonedNotes(localNotes)

            val since = syncStateStore.lastReconciledAt()
            // Captured before the scan so a note written mid-reconcile stays eligible next time.
            val highWater = System.currentTimeMillis()
            val changed = localNotes.filter { note ->
                val id = note.id ?: return@filter false
                note.timestamp > since && note.isCloudSyncEligible() && !syncStateStore.isDeleted(id)
            }

            var uploaded = 0
            for (note in changed) {
                val noteId = note.id ?: continue
                val remote = userNotesCollection(uid).document(noteId.toString()).get().await()
                val remoteServerTs = if (remote.exists()) {
                    remote.getTimestamp("serverUpdatedAt")?.toEpochMillis()
                } else {
                    null
                }
                val localServerTs = note.serverUpdatedAt
                // Only push when the cloud copy is absent or strictly older — never clobber a
                // newer remote, and skip an already-synced note (no redundant write). Prefers
                // the server-assigned timestamp over the client one for the same reason as
                // uploadAllNotes: it can't be skewed or spoofed by either device's clock.
                val remoteIsNewerOrEqual = if (remoteServerTs != null && localServerTs != null) {
                    remoteServerTs >= localServerTs
                } else {
                    val remoteTs = if (remote.exists()) remote.getLong("timestamp") else null
                    remoteTs != null && remoteTs >= note.timestamp
                }
                if (remoteIsNewerOrEqual) continue
                putCloudNote(uid, note)
                uploaded++
            }

            updateSyncMeta(uid, noteRepository.getCloudEligibleNoteCount())
            syncStateStore.markReconciled(highWater)
            Result.success(uploaded)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun uploadNote(noteId: Long): Result<Unit> {
        return try {
            val uid = sessionManager.ensureGoogleSignedIn().getOrThrow()
            refreshCloudTombstone(uid, noteId)
            if (syncStateStore.isDeleted(noteId)) {
                return deleteNote(noteId)
            }
            val note = noteRepository.getNoteById(noteId)
                ?: return Result.success(Unit)
            putCloudNote(uid, note)
            Result.success(Unit)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    /**
     * Undo of a permanent delete. [uploadNote] deliberately lets a tombstone veto an upload
     * (that is how a delete on another device propagates), so restoring has to clear both the
     * local and the cloud tombstone first — otherwise the re-created note is turned straight
     * back into a delete here, and purged locally by the next [downloadAllNotes].
     */
    suspend fun restoreNote(noteId: Long): Result<Unit> {
        return try {
            syncStateStore.clearDeleted(listOf(noteId))
            val uid = sessionManager.ensureGoogleSignedIn().getOrThrow()
            userTombstonesCollection(uid)
                .document(noteId.toString())
                .delete()
                .await()
            // Cloud tombstone is gone, so the restore marker has done its job.
            syncStateStore.clearRestored(listOf(noteId))
            val note = noteRepository.getNoteById(noteId)
                ?: return Result.success(Unit)
            putCloudNote(uid, note)
            Result.success(Unit)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun deleteNote(noteId: Long): Result<Unit> {
        return try {
            val deletedAt = System.currentTimeMillis()
            syncStateStore.markDeleted(noteId, deletedAt)
            val uid = sessionManager.ensureGoogleSignedIn().getOrThrow()
            writeCloudTombstone(uid, noteId, deletedAt)
            userNotesCollection(uid)
                .document(noteId.toString())
                .delete()
                .await()
            Result.success(Unit)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun downloadAllNotes(): Result<Int> {
        return try {
            val uid = sessionManager.ensureGoogleSignedIn().getOrThrow()
            mergeCloudTombstones(uid)
            // One read of the note table for the whole sync: purge, the merge loop below, and
            // the local-only loop all work off this snapshot.
            val localNotesBeforePurge = noteRepository.getAllNotesForBackup()
            val purgedIds = purgeLocalTombstonedNotes(localNotesBeforePurge)
            var changes = purgedIds.size
            val snapshot = userNotesCollection(uid).get().await()

            val labelMap = noteRepository.getAllLabelsSnapshot()
                .associateBy { it.name.lowercase() }
                .toMutableMap()

            suspend fun ensureLabel(name: String): Label {
                val key = name.trim().lowercase()
                labelMap[key]?.let { return it }
                val id = noteRepository.insertLabel(Label(name = name.trim()))
                val label = Label(id = id, name = name.trim())
                labelMap[key] = label
                return label
            }

            val cloudNoteIds = mutableSetOf<Long>()
            val allLocalNotes = localNotesBeforePurge.filter { note -> note.id !in purgedIds }
            val localNotesById = allLocalNotes.mapNotNull { note -> note.id?.let { it to note } }.toMap()
            val previouslyKnownCloudIds = syncStateStore.knownCloudIds()

            for (document in snapshot.documents) {
                val noteId = document.id.toLongOrNull()
                    ?: document.getLong("localId")
                    ?: continue

                if (syncStateStore.isDeleted(noteId)) {
                    userNotesCollection(uid).document(noteId.toString()).delete().await()
                    val deletedAt = syncStateStore.deletedAtById()[noteId] ?: System.currentTimeMillis()
                    writeCloudTombstone(uid, noteId, deletedAt)
                    changes++
                    continue
                }

                cloudNoteIds.add(noteId)
                val data = document.data ?: continue
                val cloudNote = data.toCloudNote(noteId) { name -> ensureLabel(name) }
                val localNote = localNotesById[noteId]

                val cloudWins = if (localNote == null) {
                    true
                } else {
                    val remoteServerTs = cloudNote.serverUpdatedAt
                    val localServerTs = localNote.serverUpdatedAt
                    // Prefer the server-assigned timestamp on both sides — a device's own clock
                    // can be wrong or spoofed, so comparing two devices' `timestamp` fields
                    // against each other is not trustworthy. Falls back to the client timestamp
                    // only for a note that hasn't round-tripped through sync since this field
                    // was introduced (both clients still populate it identically either way).
                    if (remoteServerTs != null && localServerTs != null) {
                        remoteServerTs >= localServerTs
                    } else {
                        cloudNote.timestamp >= localNote.timestamp
                    }
                }

                when {
                    localNote == null -> {
                        noteRepository.insertNoteWithResult(cloudNote)
                        changes++
                    }
                    cloudWins -> {
                        noteRepository.updateNote(cloudNote)
                        changes++
                    }
                    else -> {
                        // Local wins. Write it directly rather than via uploadNote(), which would
                        // re-resolve the uid and re-read this note's tombstone over the network —
                        // both already known here.
                        putCloudNote(uid, localNote)
                        changes++
                    }
                }
            }

            for (localNote in allLocalNotes) {
                val noteId = localNote.id ?: continue
                if (noteId in cloudNoteIds) continue
                if (syncStateStore.isDeleted(noteId)) continue

                if (noteId in previouslyKnownCloudIds) {
                    noteRepository.deleteNote(localNote)
                    val deletedAt = System.currentTimeMillis()
                    syncStateStore.markDeleted(noteId, deletedAt)
                    writeCloudTombstone(uid, noteId, deletedAt)
                    changes++
                    continue
                }

                if (localNote.isCloudSyncEligible()) {
                    putCloudNote(uid, localNote)
                    changes++
                }
            }

            syncStateStore.setKnownCloudIds(cloudNoteIds)
            pruneExpiredTombstones(uid, cloudNoteIds)
            updateSyncMeta(uid, noteRepository.getCloudEligibleNoteCount())

            Result.success(changes)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun deleteAllCloudData(): Result<Int> {
        return try {
            val uid = sessionManager.ensureGoogleSignedIn().getOrThrow()
            val snapshot = userNotesCollection(uid).get().await()
            var deleted = 0
            snapshot.documents.chunked(BATCH_LIMIT).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { document ->
                    batch.delete(document.reference)
                    deleted++
                }
                batch.commit().await()
            }
            val tombstones = userTombstonesCollection(uid).get().await()
            tombstones.documents.chunked(BATCH_LIMIT).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { document -> batch.delete(document.reference) }
                batch.commit().await()
            }
            firestore.collection("users")
                .document(uid)
                .collection("_meta")
                .document("sync")
                .delete()
                .await()
            Result.success(deleted)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun mergeCloudTombstones(uid: String) {
        val snapshot = userTombstonesCollection(uid).get().await()
        val remote = snapshot.documents.associate { doc ->
            val id = doc.id.toLongOrNull() ?: -1L
            id to (doc.getLong("deletedAt") ?: System.currentTimeMillis())
        }.filterKeys { it != -1L }

        // A restore that never managed to delete its cloud tombstone would otherwise have the
        // note purged again here. Finish that cleanup instead, and only then drop the marker.
        val restored = syncStateStore.restoredIds()
        val stale = remote.keys.filter { it in restored }
        if (stale.isNotEmpty()) {
            for (noteId in stale) {
                userTombstonesCollection(uid).document(noteId.toString()).delete().await()
            }
            syncStateStore.clearRestored(stale)
        }

        syncStateStore.mergeDeleted(remote.filterKeys { it !in restored })
    }

    private suspend fun refreshCloudTombstone(uid: String, noteId: Long) {
        // Same reason as above: an ordinary edit-and-upload of a restored note must not be
        // turned into a delete by the tombstone its own restore is still trying to clear.
        if (noteId in syncStateStore.restoredIds()) return
        val snap = userTombstonesCollection(uid).document(noteId.toString()).get().await()
        if (!snap.exists()) return
        val deletedAt = snap.getLong("deletedAt") ?: System.currentTimeMillis()
        syncStateStore.mergeDeleted(mapOf(noteId to deletedAt))
    }

    /**
     * Removes local notes that were deleted on another device (cloud tombstone).
     * Takes the caller's note snapshot and returns the purged ids so the caller can keep using
     * that snapshot instead of re-reading the table.
     */
    private suspend fun purgeLocalTombstonedNotes(localNotes: List<Note>): Set<Long> {
        val purged = mutableSetOf<Long>()
        for (note in localNotes) {
            val id = note.id ?: continue
            if (!syncStateStore.isDeleted(id)) continue
            noteRepository.deleteNote(note)
            purged.add(id)
        }
        return purged
    }

    /**
     * Writes a note we already hold, then refreshes Room's cached [Note.serverUpdatedAt] from
     * the server-resolved value. Without this readback the cache goes stale the moment this
     * device uploads its own edit — a later reconcile/download pass would then compare a fresh
     * remote timestamp against a stale local one, conclude "remote is newer", and either skip a
     * genuinely pending upload or overwrite a newer local edit with older cloud content. One
     * extra read per write; correctness here matters more than the Spark-tier read count.
     */
    private suspend fun putCloudNote(uid: String, note: Note) {
        val noteId = note.id ?: return
        val docRef = userNotesCollection(uid).document(noteId.toString())
        docRef.set(note.toCloudMap(), SetOptions.merge()).await()
        refreshServerTimestamp(docRef, noteId)
    }

    private suspend fun refreshServerTimestamp(docRef: DocumentReference, noteId: Long) {
        val resolved = docRef.get().await().getTimestamp("serverUpdatedAt")?.toEpochMillis()
        if (resolved != null) {
            noteRepository.updateServerTimestamp(noteId, resolved)
        }
    }

    private suspend fun writeCloudTombstone(
        uid: String,
        noteId: Long,
        deletedAt: Long = System.currentTimeMillis()
    ) {
        userTombstonesCollection(uid)
            .document(noteId.toString())
            .set(mapOf("deletedAt" to deletedAt), SetOptions.merge())
            .await()
    }

    private suspend fun pruneExpiredTombstones(uid: String, liveNoteIds: Set<Long>) {
        val pruned = syncStateStore.pruneExpired(NoteSyncStateStore.TOMBSTONE_TTL_MS)
            .filter { it !in liveNoteIds }
        pruned.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { noteId ->
                batch.delete(userTombstonesCollection(uid).document(noteId.toString()))
            }
            batch.commit().await()
        }
        // Also prune cloud-only expired tombstones not tracked locally.
        val cloud = userTombstonesCollection(uid).get().await()
        val now = System.currentTimeMillis()
        val expiredRemote = cloud.documents.mapNotNull { doc ->
            val id = doc.id.toLongOrNull() ?: return@mapNotNull null
            if (id in liveNoteIds) return@mapNotNull null
            val deletedAt = doc.getLong("deletedAt") ?: return@mapNotNull null
            if (now - deletedAt >= NoteSyncStateStore.TOMBSTONE_TTL_MS) id to doc.reference else null
        }
        expiredRemote.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { (_, ref) -> batch.delete(ref) }
            batch.commit().await()
        }
        syncStateStore.clearDeleted(expiredRemote.map { it.first })
    }

    private fun userNotesCollection(uid: String) = firestore.collection("users")
        .document(uid)
        .collection("notes")

    private fun userTombstonesCollection(uid: String) = firestore.collection("users")
        .document(uid)
        .collection("tombstones")

    private suspend fun updateSyncMeta(uid: String, noteCount: Int) {
        firestore.collection("users")
            .document(uid)
            .collection("_meta")
            .document("sync")
            .set(syncMetaMap(noteCount), SetOptions.merge())
            .await()
    }

    companion object {
        private const val BATCH_LIMIT = 400
    }
}
