package com.aus.notelikeus.data.remote

import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.domain.repository.NoteRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

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
            val remoteTimestamps = notesCollection.get().await().documents.associate { doc ->
                val id = doc.id.toLongOrNull() ?: doc.getLong("localId") ?: -1L
                id to (doc.getLong("timestamp") ?: 0L)
            }

            var uploaded = 0
            notes.chunked(BATCH_LIMIT).forEach { chunk ->
                val batch = firestore.batch()
                var batchCount = 0
                chunk.forEach { note ->
                    val noteId = note.id ?: return@forEach
                    val remoteTs = remoteTimestamps[noteId]
                    if (remoteTs != null && remoteTs > note.timestamp) return@forEach
                    batch.set(
                        notesCollection.document(noteId.toString()),
                        note.toCloudMap(),
                        SetOptions.merge()
                    )
                    batchCount++
                }
                if (batchCount > 0) {
                    batch.commit().await()
                    uploaded += batchCount
                }
            }

            updateSyncMeta(uid, notes.size)
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
            if (!note.isCloudSyncEligible()) {
                // Drop cloud copy without a tombstone so unlock can re-upload later.
                userNotesCollection(uid)
                    .document(noteId.toString())
                    .delete()
                    .await()
                return Result.success(Unit)
            }
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
            val note = noteRepository.getNoteById(noteId)
                ?: return Result.success(Unit)
            if (!note.isCloudSyncEligible()) return Result.success(Unit)
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

                when {
                    localNote == null -> {
                        noteRepository.insertNoteWithResult(cloudNote)
                        changes++
                    }
                    localNote.isLocked -> Unit
                    cloudNote.timestamp >= localNote.timestamp -> {
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
                    if (!localNote.isLocked) {
                        noteRepository.deleteNote(localNote)
                        val deletedAt = System.currentTimeMillis()
                        syncStateStore.markDeleted(noteId, deletedAt)
                        writeCloudTombstone(uid, noteId, deletedAt)
                        changes++
                    }
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
        syncStateStore.mergeDeleted(remote)
    }

    private suspend fun refreshCloudTombstone(uid: String, noteId: Long) {
        val snap = userTombstonesCollection(uid).document(noteId.toString()).get().await()
        if (!snap.exists()) return
        val deletedAt = snap.getLong("deletedAt") ?: System.currentTimeMillis()
        syncStateStore.mergeDeleted(mapOf(noteId to deletedAt))
    }

    /**
     * Removes unlocked local notes that were deleted on another device (cloud tombstone).
     * Takes the caller's note snapshot and returns the purged ids so the caller can keep using
     * that snapshot instead of re-reading the table.
     */
    private suspend fun purgeLocalTombstonedNotes(localNotes: List<Note>): Set<Long> {
        val purged = mutableSetOf<Long>()
        for (note in localNotes) {
            val id = note.id ?: continue
            if (!syncStateStore.isDeleted(id)) continue
            if (note.isLocked) continue
            noteRepository.deleteNote(note)
            purged.add(id)
        }
        return purged
    }

    /** Writes a note we already hold — no uid lookup, no tombstone round-trip, no DB re-read. */
    private suspend fun putCloudNote(uid: String, note: Note) {
        val noteId = note.id ?: return
        userNotesCollection(uid)
            .document(noteId.toString())
            .set(note.toCloudMap(), SetOptions.merge())
            .await()
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
