package com.aus.notelikeus.data.remote

import com.aus.notelikeus.domain.model.Label
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
    private val firestore: FirebaseFirestore
) {
    suspend fun uploadAllNotes(): Result<Int> {
        return try {
            val uid = sessionManager.ensureGoogleSignedIn().getOrThrow()
            val notes = noteRepository.getAllNotesForBackup().filter { it.isCloudSyncEligible() }
            if (notes.isEmpty()) {
                updateSyncMeta(uid, 0)
                return Result.success(0)
            }

            val notesCollection = userNotesCollection(uid)
            notes.chunked(BATCH_LIMIT).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { note ->
                    val noteId = note.id ?: return@forEach
                    batch.set(
                        notesCollection.document(note.cloudDocumentId()),
                        note.toCloudMap(),
                        SetOptions.merge()
                    )
                }
                batch.commit().await()
            }

            updateSyncMeta(uid, notes.size)
            Result.success(notes.size)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun uploadNote(noteId: Long): Result<Unit> {
        return try {
            val uid = sessionManager.ensureGoogleSignedIn().getOrThrow()
            val note = noteRepository.getNoteById(noteId)
                ?: return Result.success(Unit)
            if (!note.isCloudSyncEligible()) {
                return deleteNote(noteId)
            }
            userNotesCollection(uid)
                .document(note.cloudDocumentId())
                .set(note.toCloudMap(), SetOptions.merge())
                .await()
            Result.success(Unit)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun deleteNote(noteId: Long): Result<Unit> {
        return try {
            val uid = sessionManager.ensureGoogleSignedIn().getOrThrow()
            val note = noteRepository.getNoteById(noteId)
            val documentId = note?.cloudDocumentId() ?: noteId.toString()
            userNotesCollection(uid)
                .document(documentId)
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

            val cloudIds = mutableSetOf<String>()
            var changes = 0

            for (document in snapshot.documents) {
                val data = document.data ?: continue
                val cloudId = resolveCloudIdFromDocument(document.id, data)
                cloudIds.add(cloudId)

                val legacyLocalId = document.id.toLongOrNull()
                    ?: (data["localId"] as? Number)?.toLong()
                val localNote = noteRepository.getNoteByCloudId(cloudId)
                    ?: legacyLocalId?.let { noteRepository.getNoteById(it) }

                val resolvedLocalId = localNote?.id ?: legacyLocalId ?: 0L
                val cloudNote = data.toCloudNote(resolvedLocalId, cloudId) { name ->
                    ensureLabel(name)
                }

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
                        uploadNote(localNote.id!!)
                        changes++
                    }
                }
            }

            val localNotes = noteRepository.getAllNotesForBackup().filter { it.isCloudSyncEligible() }
            for (localNote in localNotes) {
                val noteId = localNote.id ?: continue
                if (localNote.cloudDocumentId() !in cloudIds) {
                    uploadNote(noteId)
                    changes++
                }
            }

            updateSyncMeta(uid, localNotes.size)
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

    private fun userNotesCollection(uid: String) = firestore.collection("users")
        .document(uid)
        .collection("notes")

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
